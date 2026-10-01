package com.lily.cicd.deploy;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.ProbeBuilder;

/**
 * 슬롯 Pod 의 readiness·liveness·startup probe.
 *
 * <p>경로가 {@link #TCP} 이면 HTTP 대신 포트가 열렸는지만 본다. 헬스 경로를 모르는 앱(Spring Security 로 / 가
 * 401·403 인 앱, / 가 404 인 API 서버)도 뜨기만 하면 Ready 가 되게 하려고 둔다. lily-builder 가 레포에
 * actuator 가 없고 사용자가 경로를 주지 않았을 때 보낸다.
 */
final class Probes {

    /** 경로 대신 보내는 값. / 로 시작하지 않으므로 HTTP 경로와 겹치지 않는다 */
    static final String TCP = "tcp";

    private Probes() {
    }

    static boolean isTcp(String path) {
        return TCP.equalsIgnoreCase(path == null ? "" : path.trim());
    }

    /** readiness·liveness. 5초 후 3초 간격 */
    static Probe check(String path, int port) {
        return base(path, port).withInitialDelaySeconds(5).withPeriodSeconds(3).build();
    }

    /**
     * 기동이 끝날 때까지 liveness 를 미룬다. Spring Boot 앱은 t3.medium 에서 기동에 10~20초가 걸려서,
     * liveness(5초 후 3초 간격, 3회 실패)만 있으면 뜨기 전에 재시작을 반복한다. 최대 3분(5초 x 36회) 기다린다.
     */
    static Probe startup(String path, int port) {
        return base(path, port).withPeriodSeconds(5).withFailureThreshold(36).build();
    }

    private static ProbeBuilder base(String path, int port) {
        if (isTcp(path)) {
            return new ProbeBuilder().withNewTcpSocket().withPort(new IntOrString(port)).endTcpSocket();
        }
        return new ProbeBuilder()
                .withNewHttpGet()
                    .withPath(path)
                    .withPort(new IntOrString(port))
                    .withScheme("HTTP")
                .endHttpGet();
    }
}
