# k3s 배포

lily-cicd 를 k3s 의 `lily-system` namespace 에 올린다. 클러스터 내부에서만 접근한다.

```
lily-builder → http://lily-cicd.lily-system.svc/api/deployments → k8s API (ServiceAccount)
```

## 처음 한 번

```bash
# 1. 이미지 빌드 → ECR
docker build -t <ACCOUNT_ID>.dkr.ecr.ap-northeast-2.amazonaws.com/lily-cicd:latest .
docker push <ACCOUNT_ID>.dkr.ecr.ap-northeast-2.amazonaws.com/lily-cicd:latest

# 2. 매니페스트의 image 를 ECR 주소로 바꾼 뒤
kubectl apply -f deploy/k3s/lily-cicd.yaml

# 3. 확인
kubectl -n lily-system get pods -l app=lily-cicd
kubectl -n lily-system logs deploy/lily-cicd
```

- DB 연동은 lily-db-provisioner 가 먼저 배포돼 있어야 한다 (`db-provisioner-env` Secret 의 토큰을 같이 쓴다).
  DB 없이 쓰려면 `LILY_DATABASE_*` 두 값을 지운다
- 배포 전략은 `LILY_DEPLOY_STRATEGY` (`blue-green` / `canary`)

## 코드를 고친 뒤

Dockerfile 과 매니페스트는 그대로 두고, 이미지만 다시 올리고 재시작한다.

```bash
docker build -t <ACCOUNT_ID>.dkr.ecr.ap-northeast-2.amazonaws.com/lily-cicd:latest .
docker push <ACCOUNT_ID>.dkr.ecr.ap-northeast-2.amazonaws.com/lily-cicd:latest
kubectl -n lily-system rollout restart deploy/lily-cicd
```

## 권한

배포 요청마다 namespace 가 다를 수 있어서 ClusterRole 로 준다. 코드가 쓰는 것만 연다.

| 리소스 | 동작 |
|---|---|
| Deployment | 조회, 생성, 교체, 삭제, Ready 대기, scale |
| Service | 조회, 생성, 교체 (selector 전환) |
| Ingress | 조회, 생성, 교체 (Nginx 라우팅) |
| ConfigMap | 조회, 생성, 교체, 삭제 (슬롯별 마이그레이션 스크립트 `{app}-{slot}-schema`) |
| Lease | 조회, 생성, 교체, 삭제 (배포·롤백 잠금 `lily-lock-{app}`) |

스키마 마이그레이션은 이 Pod 가 사용자 DB 에 직접 붙어 실행한다. lily-cicd 가 worker 에서 돌고 RDS 보안 그룹이 worker 를 허용해야 한다.
