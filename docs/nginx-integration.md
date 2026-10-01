# nginx 연동: CI/CD 가 넘기는 값과 nginx 쪽에서 받아야 하는 정보

CI/CD(lily-cicd)가 배포 중에 nginx 에 라우트를 넣고 바꾸려면 무엇이 오가야 하는지 정리한다.
1장은 지금 동작, 2장은 CI/CD 가 줄 수 있는 값, 3장은 nginx 담당에게 받아야 하는 정보, 4장은 CI/CD 쪽 연결 위치다.

기준: lily-cicd `main`. 클러스터에 떠 있는 버전(`lily-cicd:b7054bf`, `LILY_DEPLOY_STRATEGY=blue-green`)과 다른 곳은 따로 적었다.

## 1. 지금 동작

CI/CD 는 nginx 를 HTTP 로 부르지 않는다. k8s API 로 Service·Ingress 를 쓰고, ingress-nginx 컨트롤러가 그걸 읽어 반영한다.

```
ALB (HTTPS 종료) → ingress-nginx → Service {app}-svc (selector color=blue|green 또는 track=stable|canary) → Pod
```

| 시점 | CI/CD 가 쓰는 리소스 | 내용 |
|---|---|---|
| 새 슬롯 Ready 뒤, 전환 전 (블루그린 판정) | `main`: 없음. CI/CD 가 두 Service 에 직접 요청해서 판정한다<br>클러스터(`b7054bf`): `{app}-canary-svc` + `{app}-canary-ingress` | `b7054bf` 는 같은 host 에 `nginx.ingress.kubernetes.io/canary: "true"`, `canary-weight: "10"` 으로 사용자 트래픽 10% 를 새 색에 30초 보낸다 |
| 판정 끝 (성공·실패 모두) | canary Ingress·Service 삭제 | |
| 전환 | `{app}-svc` 의 selector 를 새 색으로 바꾼다 | nginx 설정은 바뀌지 않는다. 엔드포인트만 바뀐다 |
| 전환 직후 | `TrafficRouter.route(context)` → `{app}-ingress` upsert | host 규칙 `{host} / Prefix → {app}-svc:80`. 같은 Ingress 의 다른 host 규칙·TLS·annotation 은 그대로 둔다 |
| canary 전략 (`LILY_DEPLOY_STRATEGY=canary`, 지금은 꺼짐) | `{app}-canary-svc` + `{app}-canary-ingress` | 가중치를 0 → 100 까지 `canary-weight-percent`(1~50) 칸으로 올린 뒤 본 Service 를 새 track 으로 옮기고 canary Ingress 를 지운다 |
| cicd 재시작 | 진행 중인 배포가 없는 `*-canary-ingress` 삭제 | `DeployRecovery` |

- 이전 버전 Pod 는 종료할 때 preStop 에서 잠깐 기다린다. nginx 가 엔드포인트를 갱신하기 전에 프로세스가 죽지 않게 하려는 것이다.
- `route()` 가 실패하면 배포는 FAILED 다. 이때 Service selector 는 이미 새 색을 가리킨다.

## 2. CI/CD 가 nginx 에 넘길 수 있는 값

`DeployContext` (`com.lily.cicd.module.DeployContext`) 로 넘기는 값이다. `route()` 를 부를 때 모두 채워져 있다.

| 값 | 예 | 설명 |
|---|---|---|
| `appName` | `blog` | lily-builder 규칙 `[a-z0-9]([-a-z0-9]*[a-z0-9])?`, 55자 이하. 라우트 키로 쓰면 된다 |
| `namespace` | `default` | 앱 리소스가 있는 namespace |
| `host` | `blog.apps.lilycloud.kr` | 기본값 `{appName}.{LILY_DEPLOY_DOMAIN}`. 클라우드 버스팅은 온프레미스 공개 주소처럼 다른 host 를 넘긴다 |
| `serviceName` | `blog-svc` | upstream. 클러스터 안 주소 `blog-svc.default.svc.cluster.local` |
| `servicePort` | `80` | Service 포트. Pod 포트(`targetPort`)는 Service 가 매핑한다 |
| `targetColor` | `green` | 이번 배포가 올린 색 (블루그린) |
| `targetPort` | `8080` | 컨테이너 포트. nginx 가 Pod 로 바로 붙을 때만 필요하다 |
| `appVersion`, `imageUrl` | `20261001-101500`, `…/blog:20261001-101500` | 로그·헤더용 |

canary 가중치를 nginx 가 맡는다면 다음 값도 넘긴다.

| 값 | 예 | 설명 |
|---|---|---|
| canary upstream | `blog-canary-svc:80` | 새 슬롯만 가리키는 Service |
| weight | `0`~`100` | 새 슬롯으로 보낼 비율(%). 판정은 기본 10%, canary 전략은 설정한 칸만큼 올린다 |

## 3. nginx 담당에게 받아야 하는 정보

CI/CD 가 nginx 를 직접 부르려면 아래 정보가 정해져 있어야 한다.

### 3.1 위치와 역할

- 이 nginx 가 ingress-nginx 를 **대체**하는지, ALB 와 ingress-nginx **사이**에 서는지, **온프레미스** 앞에 서는지
- 대체한다면 기존 `*-ingress` 들을 어떻게 옮길지와 전환하는 동안 둘 다 받을지
- TLS 종료 위치. 지금은 ALB(ACM `*.apps.lilycloud.kr`)에서 끝나고 Ingress 에는 TLS 가 없다

### 3.2 호출 주소와 인증

- 제어 API 주소. CI/CD 는 `lily-system` 의 Pod 이다. 클러스터 안 주소(`http://<svc>.<ns>.svc`)면 NetworkPolicy 에서 `lily-system → 그 Pod` 를 열어야 한다
- 인증: 헤더 이름과 토큰 형식. 토큰은 k8s Secret 으로 CI/CD 에 넣는다 (Secret 이름, key)
- upstream 으로 클러스터 Service DNS(`{svc}.{ns}.svc.cluster.local:80`)를 쓸 수 있는지, 아니면 Pod IP·NodePort 가 필요한지

### 3.3 API 계약

배포 흐름에 필요한 동작은 네 가지다. 경로와 본문은 nginx 쪽에서 정해 주면 된다. 아래는 필요한 내용의 예시다.

| 동작 | 예시 | CI/CD 가 부르는 시점 |
|---|---|---|
| 라우트 넣기·바꾸기 | `PUT /routes/{appName}` `{"host":"blog.apps.lilycloud.kr","upstream":"blog-svc.default.svc.cluster.local:80"}` | 전환 직후 (`route()`) |
| 가중치 넣기·바꾸기 | `PUT /routes/{appName}/canary` `{"upstream":"blog-canary-svc.default.svc.cluster.local:80","weight":10}` | 판정 시작, canary 전략의 각 칸 |
| 가중치 지우기 | `DELETE /routes/{appName}/canary` | 판정 끝, 실패 정리, cicd 재시작 정리 |
| 라우트 조회 | `GET /routes/{appName}` | 판정 전 host 확인, 복구 |

각 동작에 대해 아래를 정해 줘야 한다.

- **멱등성**: 같은 요청을 다시 보내도 결과가 같아야 한다. CI/CD 는 응답이 끊기면 다시 보낸다
- **반영 시점**: `200` 이 오면 이미 트래픽에 반영된 상태인지(reload 완료), 반영까지 걸리는 최대 시간은 얼마인지. 반영 전에 판정을 시작하면 표본이 틀어진다
- **reload 방식**: reload 중에 열려 있는 연결과 WebSocket(에이전트 소켓은 timeout 3600초)이 끊기는지
- **한 host 에 여러 앱**: 같은 host 를 다른 appName 이 요청하면 거절(`409`)할지 덮어쓸지
- **실패 응답**: 상태 코드, 본문 형식(팀 공통 `{timestamp, code, message}` 이면 좋다), 다시 보내도 되는 실패(`5xx`)와 안 되는 실패(`4xx`) 구분
- **타임아웃**: 요청 하나에 걸릴 수 있는 최대 시간

### 3.4 앱 삭제

- 앱을 지울 때 라우트도 지우는 동작(`DELETE /routes/{appName}`)과, 그걸 CI/CD 가 부를지 다른 모듈이 부를지

## 4. CI/CD 쪽 연결 위치

- 라우트: `TrafficRouter` 를 구현한 빈(예: `HttpNginxRouter`)을 두면 기본 `NginxIngressRouter` 대신 쓰인다 (`ModuleConfiguration`). 호출 시점은 `DeploymentEngine.route()` (전환 직후) 한 곳이다.
- 가중치: 지금 `TrafficRouter` 에는 가중치 동작이 없다. nginx 가 가중치를 맡으면 인터페이스에 `weight(context, upstream, percent)`, `clearWeight(appName)` 같은 동작을 더하고, `CanaryDeploymentStrategy`·`CanaryAnalysis`·`DeployRecovery` 에서 Ingress 를 직접 쓰는 곳을 그 호출로 바꾼다.
- 위 3장 정보가 오면 그에 맞춰 구현한다.
