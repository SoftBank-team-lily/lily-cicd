package com.lily.cicd.schema;

import org.flywaydb.core.api.MigrationVersion;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 한 릴리스의 마이그레이션 파일 전체 (앱 커밋의 V, U).
 * 배포 요청의 {@code migrations} 에서 만들고, 롤백에 쓰려고 ConfigMap 에 그대로 보관한다.
 */
public final class MigrationSet {

    /** ConfigMap 한 개(1MiB)에 들어가야 한다 */
    static final int MAX_TOTAL_BYTES = 900 * 1024;

    private static final Pattern NAME = Pattern.compile("([VU])(\\d+(?:[._]\\d+)*)__([\\w.-]+)\\.sql");
    private static final MigrationSet EMPTY = new MigrationSet(Map.of(), new TreeMap<>(), new TreeMap<>());

    private final Map<String, String> files;
    private final TreeMap<MigrationVersion, MigrationScript> versioned;
    private final TreeMap<MigrationVersion, MigrationScript> undo;

    private MigrationSet(Map<String, String> files,
                         TreeMap<MigrationVersion, MigrationScript> versioned,
                         TreeMap<MigrationVersion, MigrationScript> undo) {
        this.files = files;
        this.versioned = versioned;
        this.undo = undo;
    }

    public static MigrationSet empty() {
        return EMPTY;
    }

    /**
     * 파일명 규칙, V 버전 중복, 짝 없는 U, 합계 크기를 본다. 위반은 모아서 한 번에 알린다.
     *
     * @throws IllegalArgumentException 규칙 위반 (배포 요청 400)
     */
    public static MigrationSet parse(Map<String, String> files) {
        if (files == null || files.isEmpty()) {
            return EMPTY;
        }
        List<String> errors = new ArrayList<>();
        TreeMap<MigrationVersion, MigrationScript> versioned = new TreeMap<>();
        TreeMap<MigrationVersion, MigrationScript> undo = new TreeMap<>();
        Map<String, String> sorted = new TreeMap<>();
        long bytes = 0;

        for (Map.Entry<String, String> entry : files.entrySet()) {
            String name = entry.getKey();
            String sql = entry.getValue() == null ? "" : entry.getValue();
            if (name == null || name.isBlank()) {
                errors.add("파일명이 비어 있다");
                continue;
            }
            bytes += name.getBytes(StandardCharsets.UTF_8).length + sql.getBytes(StandardCharsets.UTF_8).length;
            if (name.startsWith("R__")) {
                errors.add(name + ": repeatable(R) 마이그레이션은 되돌릴 수 없어 지원하지 않는다");
                continue;
            }
            Matcher m = NAME.matcher(name);
            if (!m.matches()) {
                errors.add(name + ": 파일명은 V{버전}__{설명}.sql 또는 U{버전}__{설명}.sql 이어야 한다");
                continue;
            }
            MigrationScript.Kind kind = "V".equals(m.group(1)) ? MigrationScript.Kind.VERSIONED : MigrationScript.Kind.UNDO;
            MigrationScript script = new MigrationScript(name, kind, MigrationVersion.fromVersion(m.group(2)), sql);
            TreeMap<MigrationVersion, MigrationScript> target = kind == MigrationScript.Kind.VERSIONED ? versioned : undo;
            MigrationScript previous = target.putIfAbsent(script.version(), script);
            if (previous != null) {
                errors.add(name + ": " + previous.fileName() + " 와 버전이 같다");
            }
            sorted.put(name, sql);
        }
        for (MigrationScript u : undo.values()) {
            if (!versioned.containsKey(u.version())) {
                errors.add(u.fileName() + ": 같은 버전의 V 파일이 없다");
            }
        }
        if (bytes > MAX_TOTAL_BYTES) {
            errors.add("마이그레이션 합계 " + bytes / 1024 + "KiB 가 " + MAX_TOTAL_BYTES / 1024 + "KiB 를 넘는다");
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("migrations 규칙 위반: " + String.join("; ", errors));
        }
        return new MigrationSet(Map.copyOf(sorted), versioned, undo);
    }

    public boolean isEmpty() {
        return versioned.isEmpty();
    }

    /** 원본 그대로 (파일명 → SQL). ConfigMap 에 보관할 때 쓴다 */
    public Map<String, String> files() {
        return files;
    }

    public Collection<MigrationScript> versionedScripts() {
        return versioned.values();
    }

    public Optional<MigrationScript> versioned(MigrationVersion version) {
        return Optional.ofNullable(versioned.get(version));
    }

    public Optional<MigrationScript> undo(MigrationVersion version) {
        return Optional.ofNullable(undo.get(version));
    }

    /** DB 에 아직 적용되지 않은 V. 버전 오름차순 */
    public List<MigrationScript> pending(Set<MigrationVersion> applied) {
        return versioned.values().stream().filter(s -> !applied.contains(s.version())).toList();
    }

    /**
     * 이 버전들을 되돌릴 수 없는 이유. 되돌릴 수 있으면 빈 값.
     * 버전마다 이 릴리스에 V 와 U 가 있고, V 가 irreversible 이 아니어야 한다.
     */
    public Optional<String> undoBlocker(Collection<MigrationVersion> versions) {
        List<String> reasons = new ArrayList<>();
        for (MigrationVersion v : versions) {
            MigrationScript forward = versioned.get(v);
            if (forward == null) {
                reasons.add("v" + v + ": 이 릴리스에 스크립트가 없다");
            } else if (forward.irreversible()) {
                reasons.add("v" + v + ": " + forward.fileName() + " 는 lily:irreversible 이다");
            } else if (!undo.containsKey(v)) {
                reasons.add("v" + v + ": U 스크립트가 없다");
            }
        }
        return reasons.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", reasons));
    }

    /** 이 버전들의 U 를 버전 내림차순으로. {@link #undoBlocker} 를 먼저 확인한다 */
    public List<MigrationScript> undoScripts(Collection<MigrationVersion> versions) {
        return versions.stream()
                .sorted(Comparator.reverseOrder())
                .map(v -> undo(v).orElseThrow(() -> new IllegalStateException("U 스크립트가 없다: v" + v)))
                .toList();
    }
}
