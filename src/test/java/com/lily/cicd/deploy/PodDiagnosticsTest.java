package com.lily.cicd.deploy;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PodDiagnosticsTest {

    @Test
    void 컨테이너에_넣은_값과_비밀번호_형태를_가린다() {
        Set<String> hidden = Set.of("gdvQ4hWLfhKGBkk");

        assertEquals("connect failed for user p_1 with ***",
                PodDiagnostics.redact("connect failed for user p_1 with gdvQ4hWLfhKGBkk", hidden));
        assertEquals("url=postgresql://p_1:***@host:5432/db",
                PodDiagnostics.redact("url=postgresql://p_1:s3cr3tvalue@host:5432/db", hidden));
        assertEquals("jwt.secret=*** api_key: ***",
                PodDiagnostics.redact("jwt.secret=abcdef123 api_key: xyz987654", hidden));
        assertEquals("Could not resolve placeholder 'jwt.secret' in value \"${jwt.secret}\"",
                PodDiagnostics.redact("Could not resolve placeholder 'jwt.secret' in value \"${jwt.secret}\"", hidden));
    }
}
