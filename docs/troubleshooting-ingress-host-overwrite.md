# cicd 재배포 시 수동으로 추가한 Ingress 호스트가 사라지는 문제 (2026.10.01 이현수 작성)

2026-10-01 · lily-cicd Router 모듈 / lily-loadbalancer

## 1. 문제

앱을 다시 배포하면 로드밸런싱 쪽에서 손으로 추가해 둔 접속 주소가 사라지고, 그 주소로는 404 가 나는 문제

- Ingress 는 "이 도메인으로 들어온 요청을 어느 Service 로 보낼지" 적어 둔 목록이다. 앱마다 `{app}-ingress` 가 하나 있다
- 당시 cicd 는 배포 마지막에 `NginxIngressRouter` 가 `{app}-ingress` 를 **호스트 하나짜리로 새로 만들어 통째로 교체(createOrReplace)** 했다. 호스트는 요청의 `host`, 없으면 `{app}.{LILY_DEPLOY_DOMAIN}` (지금 `apps.lilycloud.kr`). 지금은 그 호스트 규칙만 갈아 끼우고, 다른 호스트와 TLS 는 유지한다
- 그런데 lily-loadbalancer 쪽에서 같은 Ingress 에 호스트를 손으로 추가해 뒀다 (`blog.43.200.152.53.nip.io` 등, `kubectl apply`)
- 같은 리소스를 cicd(fabric8)와 사람(kubectl)이 같이 고치고 있어서, cicd 가 쓸 때마다 사람이 넣은 내용이 덮이는 구조임을 확인 (managedFields 에 `fabric8-kubernetes-client`, `kubectl-client-side-apply` 둘 다 있음)

DB 접속 정보 Secret 전환(#7) 반영 때 blog, blog2, lily-test 를 다시 배포하니 세 앱 모두 `nip.io` 호스트가 사라졌다. 미리 떠 둔 백업으로 복원해서 지금은 정상이지만, 누가 builder 로 다시 배포하면 또 사라진다. lily-loadbalancer 레포의 매니페스트와 실제 클러스터도 그때마다 어긋난다.

## 2. 선택지

### A. cicd 가 기존 호스트를 유지하게 수정

배포할 때 기존 Ingress 를 읽어서 자기 호스트만 추가·갱신하고 나머지는 남기는 방법. Router 한 곳만 고치면 된다.
다만 사람이 넣은 호스트를 cicd 가 지울 방법이 없어 안 쓰는 호스트가 쌓이고, 누가 관리하는 값인지 여전히 섞인다. 레포와 클러스터도 계속 어긋남.

### B. cicd 는 Ingress 를 건드리지 않고 lily-loadbalancer 만 관리

관리 주체는 깔끔하다.
하지만 새 앱마다 lily-loadbalancer 에 매니페스트를 먼저 올려야 접속이 된다. "레포 주소만 넣으면 URL 이 나온다" 는 목표와 안 맞아서 제외.

### C. Ingress 를 주인별로 나누기

cicd 는 지금처럼 `{app}-ingress` (기본 호스트) 만 관리하고, 사람이 추가하는 호스트는 별도 Ingress `{app}-ingress-extra` 에 두고 lily-loadbalancer 레포에서만 관리하는 방법.
cicd 코드 수정이 없고, 리소스마다 주인이 하나라 서로 덮을 일이 없다.

실제 클러스터에서 확인한 것 (blog, 테스트 호스트 `blog-extratest.43.200.152.53.nip.io`):

| 확인 | 결과 |
|---|---|
| 같은 Service 를 가리키는 Ingress 2개 | 둘 다 200, 기존 주소도 200 그대로 |
| cicd 로 blog 를 2번 재배포 (blue→green→blue) | `blog-ingress-extra` 는 그대로 남고 새 색을 따라감. `blog-ingress` 의 `nip.io` 는 예상대로 404 |
| 같은 호스트를 두 Ingress 에 적기 | ingress-nginx admission webhook 이 거부 (`host ... and path "/" is already defined in ingress default/blog-ingress`) |

같은 호스트를 양쪽에 적는 실수는 클러스터가 막아 주므로 조용히 충돌할 일은 없다. 확인 후 테스트 Ingress 는 지우고 blog 의 `nip.io` 는 백업으로 복원했다.

## 3. 해결 → C 제안 (Alal11 님과 합의 필요)

- `{app}-ingress` 는 cicd 전용. 손으로 고치지 않는다
- 추가 호스트(`nip.io`, 커스텀 도메인 등)는 `{app}-ingress-extra` 에만 적고 lily-loadbalancer 레포에서 관리
- `{app}.apps.lilycloud.kr` 는 cicd 가 넣으므로 extra 에 적지 않는다 (적으면 webhook 이 거부)
- 합의가 늦어지면 임시로 A 를 적용해 사라지는 것만 막아 두자

## 4. 같이 발견한 것: 블루그린 전환 순간 502

위 재배포 중 두 번째 전환 직후 `/api/posts` 가 502 를 1건 받았다. ingress-nginx 로그를 보면 이전 슬롯 Pod IP 로 보낸 요청이 `upstream timed out` 으로 실패했다.
cicd 가 Service selector 를 바꾸자마자 이전 슬롯을 0 으로 내리는데, nginx 가 endpoint 목록을 갱신하기 전에 아직 내려가는 Pod 로 요청을 보내서 생기는 것으로 보인다. extra Ingress 와는 무관하고 같은 Service 를 쓰는 모든 주소에 해당한다.
selector 를 바꾼 뒤 `lily.deploy.drain-seconds`(기본 5초) 동안 이전 슬롯을 Ready 로 두고, 같은 시간만큼 Pod `preStop` 에서 `sleep` 한다. 그 다음에 replica 를 0 으로 내린다.

## 5. 할 일

* [ ] Ingress 관리 규칙 합의 (이현수, Alal11)
* [ ] lily-loadbalancer: blog, blog2, lily-test 의 `nip.io` 호스트를 `{app}-ingress-extra` 로 이동
* [ ] lily-cicd README 의 Router 모듈 설명에 "`{app}-ingress` 는 cicd 전용" 명시
* [ ] 적용 후 blog 를 builder 로 다시 배포해서 두 주소 모두 200 인지 확인
* [x] 블루그린 전환 순간 502 (4번) — `drain-seconds` 동안 이전 슬롯을 유지하고 Pod `preStop` 에서 같은 시간 대기
