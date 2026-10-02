package com.lily.cicd.module;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPath;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBackendBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRule;
import io.fabric8.kubernetes.client.KubernetesClient;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 클라우드 앱의 공개 주소({app}.apps...)는 그대로 두고, 그 Ingress 가 요청을 온프레미스 공개 주소로 넘기게 한다.
 *
 * <pre>
 * 사용자 → {app}.apps.{도메인} → ALB → ingress-nginx → {app}-onprem (ExternalName {host}:443) → Cloudflare 터널 → 사용자 PC
 * </pre>
 *
 * DNS 를 바꾸지 않는 이유: 와일드카드 *.apps 는 ALB(ACM 인증서)를 가리키고, 앱 하나만 Cloudflare 로 돌리면
 * 2단계 서브도메인이라 플랫폼 존의 범용 인증서가 덮지 못한다.
 *
 * <p>Cloudflare 는 SNI 가 없으면 TLS 를 거절한다. ingress-nginx 는 proxy-ssl-secret 이 있을 때만 SNI 를 보내므로,
 * 이 JVM 의 기본 신뢰 저장소로 CA 묶음 Secret({@value #CA_SECRET})을 만들어 걸고 인증서를 확인한다.
 */
public final class OnPremUpstream {

    public static final String SERVICE_SUFFIX = "-onprem";
    static final String CA_SECRET = "lily-public-ca";
    private static final String PREFIX = "nginx.ingress.kubernetes.io/";
    /** 넘길 때 다는 주석. 되돌릴 때와 다시 배포할 때 이 키들을 지운다 */
    static final List<String> ANNOTATIONS = List.of(
            PREFIX + "backend-protocol",
            PREFIX + "upstream-vhost",
            PREFIX + "proxy-ssl-server-name",
            PREFIX + "proxy-ssl-name",
            PREFIX + "proxy-ssl-secret",
            PREFIX + "proxy-ssl-verify",
            PREFIX + "proxy-ssl-verify-depth");
    private static final Pattern HOST = Pattern.compile("(?=.{1,253}$)[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?)+");

    private final KubernetesClient k8s;

    public OnPremUpstream(KubernetesClient k8s) {
        this.k8s = k8s;
    }

    /**
     * 앱 Ingress 의 모든 호스트를 온프레미스 주소로 넘긴다. 이미 넘겨져 있으면 주소만 바꾼다.
     *
     * @param host 온프레미스 공개 호스트 (예: blog-1a2b3c.lilycloud.kr). 스킴·경로 없이
     * @throws IllegalArgumentException 호스트 형식이 아니다
     * @throws IllegalStateException    앱 Ingress 가 없다
     */
    public void point(String namespace, String appName, String host) {
        String target = host == null ? "" : host.trim().toLowerCase();
        if (!HOST.matcher(target).matches()) {
            throw new IllegalArgumentException("온프레미스 호스트가 아니다: " + host);
        }
        Ingress ingress = ingress(namespace, appName)
                .orElseThrow(() -> new IllegalStateException(appName + "-ingress 가 없다"));
        ensureCaBundle(namespace);
        Service external = new ServiceBuilder()
                .withNewMetadata()
                    .withName(appName + SERVICE_SUFFIX)
                    .withNamespace(namespace)
                    .addToLabels("app", appName)
                    .addToLabels("lily.route", "onprem")
                .endMetadata()
                .withNewSpec()
                    .withType("ExternalName")
                    .withExternalName(target)
                    .addNewPort().withName("https").withPort(443).withTargetPort(new IntOrString(443)).endPort()
                .endSpec()
                .build();
        k8s.services().inNamespace(namespace).resource(external).createOrReplace();

        Map<String, String> annotations = annotations(ingress);
        annotations.put(PREFIX + "backend-protocol", "HTTPS");
        annotations.put(PREFIX + "upstream-vhost", target);
        annotations.put(PREFIX + "proxy-ssl-server-name", "on");
        annotations.put(PREFIX + "proxy-ssl-name", target);
        annotations.put(PREFIX + "proxy-ssl-secret", namespace + "/" + CA_SECRET);
        annotations.put(PREFIX + "proxy-ssl-verify", "on");
        annotations.put(PREFIX + "proxy-ssl-verify-depth", "3");
        ingress.getMetadata().setAnnotations(annotations);
        backends(ingress, appName + SERVICE_SUFFIX, 443);
        k8s.network().v1().ingresses().inNamespace(namespace).resource(ingress).update();
    }

    /**
     * 앱 Ingress 를 클러스터 Service({app}-svc:80) 로 되돌리고 ExternalName Service 를 지운다.
     * 넘겨져 있지 않으면 아무것도 하지 않는다.
     *
     * @return 되돌렸으면 true
     */
    public boolean restore(String namespace, String appName) {
        Optional<Ingress> found = ingress(namespace, appName);
        boolean changed = false;
        if (found.isPresent() && current(found.get()).isPresent()) {
            Ingress ingress = found.get();
            ingress.getMetadata().setAnnotations(withoutOnPrem(annotations(ingress)));
            backends(ingress, appName + "-svc", 80);
            k8s.network().v1().ingresses().inNamespace(namespace).resource(ingress).update();
            changed = true;
        }
        if (!k8s.services().inNamespace(namespace).withName(appName + SERVICE_SUFFIX).delete().isEmpty()) {
            changed = true;
        }
        return changed;
    }

    /** 지금 넘기는 온프레미스 호스트. 넘기지 않으면 비어 있다 */
    public Optional<String> current(String namespace, String appName) {
        return ingress(namespace, appName).flatMap(OnPremUpstream::current);
    }

    /** 다시 배포할 때 Ingress 주석에서 온프레미스로 넘기던 설정을 뺀다 */
    public static Map<String, String> withoutOnPrem(Map<String, String> annotations) {
        Map<String, String> clean = new HashMap<>(annotations);
        ANNOTATIONS.forEach(clean::remove);
        return clean;
    }

    private static Optional<String> current(Ingress ingress) {
        Map<String, String> annotations = ingress.getMetadata() == null ? null : ingress.getMetadata().getAnnotations();
        String vhost = annotations == null ? null : annotations.get(PREFIX + "upstream-vhost");
        boolean routed = paths(ingress).stream().anyMatch(path -> path.getBackend() != null
                && path.getBackend().getService() != null
                && path.getBackend().getService().getName() != null
                && path.getBackend().getService().getName().endsWith(SERVICE_SUFFIX));
        return routed && vhost != null && !vhost.isBlank() ? Optional.of(vhost) : Optional.empty();
    }

    private Optional<Ingress> ingress(String namespace, String appName) {
        return Optional.ofNullable(k8s.network().v1().ingresses().inNamespace(namespace)
                .withName(appName + "-ingress").get());
    }

    private static Map<String, String> annotations(Ingress ingress) {
        Map<String, String> annotations = ingress.getMetadata().getAnnotations();
        return annotations == null ? new HashMap<>() : new HashMap<>(annotations);
    }

    private static void backends(Ingress ingress, String service, int port) {
        for (HTTPIngressPath path : paths(ingress)) {
            path.setBackend(new IngressBackendBuilder()
                    .withNewService().withName(service).withNewPort().withNumber(port).endPort().endService()
                    .build());
        }
    }

    private static List<HTTPIngressPath> paths(Ingress ingress) {
        if (ingress.getSpec() == null || ingress.getSpec().getRules() == null) {
            return List.of();
        }
        return ingress.getSpec().getRules().stream()
                .map(IngressRule::getHttp)
                .filter(http -> http != null && http.getPaths() != null)
                .flatMap(http -> http.getPaths().stream())
                .toList();
    }

    /** 네임스페이스마다 한 번. 이미 있으면 그대로 둔다 */
    private void ensureCaBundle(String namespace) {
        if (k8s.secrets().inNamespace(namespace).withName(CA_SECRET).get() != null) {
            return;
        }
        Secret secret = new SecretBuilder()
                .withNewMetadata().withName(CA_SECRET).withNamespace(namespace)
                    .addToLabels("lily.route", "onprem").endMetadata()
                .addToData("ca.crt", Base64.getEncoder().encodeToString(caBundle().getBytes(StandardCharsets.US_ASCII)))
                .build();
        k8s.secrets().inNamespace(namespace).resource(secret).create();
    }

    /** 이 JVM 의 기본 신뢰 저장소에 있는 루트 인증서를 PEM 으로 */
    static String caBundle() {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            StringBuilder pem = new StringBuilder();
            Base64.Encoder base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
            for (var manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager x509) {
                    for (X509Certificate cert : x509.getAcceptedIssuers()) {
                        pem.append("-----BEGIN CERTIFICATE-----\n")
                                .append(base64.encodeToString(cert.getEncoded()))
                                .append("\n-----END CERTIFICATE-----\n");
                    }
                }
            }
            if (pem.isEmpty()) {
                throw new IllegalStateException("신뢰 저장소에 인증서가 없다");
            }
            return pem.toString();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("CA 묶음을 만들지 못했다: " + e.getMessage(), e);
        }
    }
}
