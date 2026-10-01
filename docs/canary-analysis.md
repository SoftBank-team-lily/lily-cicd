# Canary 판정 (블루그린 전환 전)

블루그린은 새 색이 Ready 가 되면 트래픽을 한 번에 옮긴다. Ready 는 "떴다"만 확인하므로, 떴지만 요청에 5xx 를 내거나
느린 버전도 그대로 100% 가 된다. 그래서 전환 전에 cicd 가 클러스터 안에서 새 버전과 이전 버전을 비교한다.
사용자 트래픽은 판정이 끝날 때까지 이전 색에 둔다.

## 순서

```
새 색 Ready
  → {app}-canary-svc 를 만들고 첫 응답까지 대기 (최대 15초, 표본에 넣지 않음)
     엔드포인트가 붙기 전에 요청하면 연결이 거절되어 에러로 잡힌다 (실측: 120건 중 3건)
  → canary-traffic   사용자 트래픽은 이전 색 그대로. cicd 만 canary Service 로 프로브한다
  → canary-analysis  30초 동안 새 색과 이전 색에 같은 요청을 250ms 간격으로 보내 에러율·p95 측정
                     새 Pod 재시작, Ready 이탈 확인
  → 통과: Service selector 를 새 색으로 (100%), canary Service 삭제, 드레인 후 이전 색 0
  → 실패: canary Service 삭제, 새 Deployment 삭제, 이번 스키마 변경 되돌림. 트래픽은 이전 색 그대로
```

| 리소스 | 내용 |
|---|---|
| `{app}-canary-svc` | selector `app={app}, color={새 색}` |
| `{app}-canary-ingress` | 더 이상 만들지 않는다. 남아 있으면 판정이 끝날 때 지운다 |

판정이 끝나면 Service 를 지운다. 실패든 통과든, 예외가 나도 지운다. 삭제가 실패하면 세 번까지 다시 시도한다.
프로세스가 그 전에 죽으면 다음 cicd 가 진행 맥박이 멈춘 앱의 canary 를 지운다.

## 판정 기준

새 버전(new)과 이전 버전(old)에 같은 경로로 같은 수의 요청을 보낸다.

| 실패 조건 | 기본값 |
|---|---|
| 새 Pod 재시작 또는 Ready 수 부족 | |
| 새 버전 응답 수 < `min-samples` | 20 |
| 새 버전 에러율(5xx, 연결 실패, 시간 초과) > `max-error-rate` | 5% |
| 새 버전 p95 > `max-p95-millis` | 1000ms |
| 새 버전 p95 > 이전 p95 × `max-p95-ratio` 이고 차이 ≥ `min-p95-regression-millis` (이전 버전이 정상일 때만) | 2.0배, 100ms |
| 표본 10개 이상에서 에러율 50% 이상이면 30초를 채우지 않고 바로 실패 | |

판정 경로는 배포 요청의 `canaryPath`, 없으면 readiness 경로다. 앱의 주요 API 경로를 넣어야 의미가 있다.

### 판정 요청을 cicd 가 직접 보내는 이유

- ingress-nginx 요청 메트릭(`nginx_ingress_controller_requests`)이 이 클러스터에서 수집되지 않는다 (2026-10-01 확인, 컨트롤러 v1.12.1)
- 사용자 트래픽이 적으면 30초 동안 10% 로는 표본이 모자라다
- 두 버전에 같은 요청을 같은 시각에 보내면 비교 기준이 같다

판정 수치는 cicd 가 클러스터 안에서 보낸 요청 기준이다. 공개 주소의 사용자 요청은 판정 동안 이전 색으로 간다.
`weight-percent` 는 이 판정에서 쓰지 않는다. 파드 비율 전략(`lily.deploy.strategy=canary`)의 가중치와는 별개다.

## 건너뛰는 경우

| 로그 | 이유 |
|---|---|
| `canary: skipped — first release` | 비교할 이전 색이 없다 |
| `canary: skipped — previous blue has no ready pods` | 이전 색이 0 (버스팅 대기 배포 등) |
| `canary: skipped — no ingress for {host} yet` | 호스트가 바뀌었거나 Ingress 가 아직 없다 |
| `canary: skipped — disabled` | `lily.deploy.canary-analysis.enabled=false` |

## API

| 호출 | 결과 |
|---|---|
| `POST /api/deployments` 통과 | 200 `SUCCESS`, logs 에 `canary: PASS new ... / old ...` |
| `POST /api/deployments` 실패 | 422 `ROLLED_BACK`, logs 에 `canary: FAIL {이유} — new ... / old ...` |
| `GET /api/deployments/{app}/progress` | 진행 중이거나 마지막 배포의 단계 `{stage, detail, updatedAt}`. stage 는 `ready`, `canary-traffic`, `canary-analysis`, `service` 등 |

배포 요청은 끝날 때까지 응답하지 않으므로, lily-builder 는 진행 단계를 progress 로 따로 묻는다.

## 설정 (`lily.deploy.canary-analysis.*`)

| 키 | 기본값 |
|---|---|
| `enabled` | `true` |
| `weight-percent` | `10` |
| `duration-seconds` | `30` |
| `interval-millis` | `250` |
| `request-timeout-millis` | `2000` |
| `max-error-rate` | `0.05` |
| `max-p95-millis` | `1000` |
| `max-p95-ratio` | `2.0` |
| `min-p95-regression-millis` | `100` |
| `min-samples` | `20` |

`lily.deploy.strategy=canary` 도 비율을 올리기 전에 이 30초 판정을 한다. 그동안 사용자 트래픽은 이전 트랙에 남고, 판정은 새 슬롯 Service 와 본 Service 를 클러스터 안에서 비교한다. 실패하면 새 Deployment 를 지우고 비율은 올리지 않는다. 통과한 뒤에 입구 비율을 0에서 100까지 올린다.

## 확인 (2026-10-01, 실제 k3s, lily-builder 로 lily-blog-sample 배포)

| 경우 | 결과 |
|---|---|
| 첫 배포 | `canary: skipped`, 완료 `https://canary-demo.apps.lilycloud.kr` |
| 재배포, `canaryPath=/whoami` | `PASS new 120 req, error 0.0%, p95 10ms / old 120 req, error 0.0%, p95 10ms` → 100% 전환 |
| 판정 중 공개 주소 요청 | 10초 구간별 새 버전 1/27, 3/25, 2/16 (약 9%), 전환 뒤 100% |
| 재배포, `canaryPath=/chaos/error` | 요청 11개 만에 `FAIL error rate over 50.0%` → 422 `ROLLED_BACK`. 새 Deployment 삭제, selector 는 이전 색, canary Ingress·Service 삭제, 공개 주소 20건 모두 이전 색 |

- 준비 대기 없이 판정했을 때 새 버전 에러 2.5% (120건 중 3건, 이전 버전 0%). canary Service 엔드포인트가 붙기 전 요청이었다 → 첫 응답까지 기다린 뒤 판정하도록 바꾼 뒤 0%
- `/chaos/error` 는 이전 버전에서도 5xx 다. 잘못된 버전을 흉내 낸 것이다. 실제로는 버그가 있는 브랜치를 배포하고 그 기능 경로를 `canaryPath` 로 준다
- 전환 순간 공개 주소 요청 400건 중 2건이 응답 본문 없이 끝났다. 전환 직후 이전 색을 0 으로 줄이는 블루그린 기존 순서에서 생기는 것으로 보이며, 이 변경과 별개로 남은 과제다
