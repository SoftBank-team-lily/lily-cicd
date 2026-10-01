package com.lily.cicd.deploy;

import io.fabric8.kubernetes.api.model.Probe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbesTest {

    @Test
    void 경로가_있으면_HTTP_로_확인한다() {
        Probe probe = Probes.check("/actuator/health/readiness", 8080);

        assertEquals("/actuator/health/readiness", probe.getHttpGet().getPath());
        assertEquals(8080, probe.getHttpGet().getPort().getIntVal());
        assertNull(probe.getTcpSocket());
        assertEquals(5, probe.getInitialDelaySeconds());
        assertEquals(3, probe.getPeriodSeconds());
    }

    @Test
    void tcp_면_포트가_열렸는지만_본다() {
        Probe check = Probes.check("tcp", 8080);
        Probe startup = Probes.startup("TCP", 3000);

        assertNull(check.getHttpGet());
        assertEquals(8080, check.getTcpSocket().getPort().getIntVal());
        assertEquals(3000, startup.getTcpSocket().getPort().getIntVal());
        assertEquals(36, startup.getFailureThreshold());
    }

    @Test
    void tcp_는_HTTP_경로와_겹치지_않는다() {
        assertTrue(Probes.isTcp(" tcp "));
        assertFalse(Probes.isTcp("/tcp"));
        assertFalse(Probes.isTcp(null));
    }
}
