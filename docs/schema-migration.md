# 스키마 마이그레이션과 롤백

배포 한 건은 **(앱 이미지, 스키마 버전)** 한 쌍이다. 배포는 둘을 함께 앞으로, 롤백은 둘을 함께 직전 쌍으로 옮긴다.
롤백은 **스키마만** 되돌린다. 배포 이후 사용자가 쓴 행은 남긴다.
PostgreSQL 앱은 pgroll 파일로 보내면 두 버전이 함께 쓰는 무중단 방식(7 절)을 쓴다.

```text
배포   DB 준비 → [MIGRATION] → 슬롯 적용 → Ready → 트래픽 전환 → Router → Monitor → 이전 슬롯 0
                    │              └ 실패하면 적용한 버전을 U 스크립트로 되돌린다 (자동)
                    └ lint → dry-run(PostgreSQL) → flyway migrate → 스크립트 보관

롤백   잠금 → 사전 검사 → 이전 슬롯 1 → Ready → selector 전환 → 백업 → U 역순 실행 → 현재 슬롯 0
```

## 1. 스크립트 규칙

마이그레이션은 앱 레포의 `src/main/resources/db/migration` (lily-builder `migrationsPath` 로 변경) 에 둔다.

| 파일 | 의미 |
|---|---|
| `V{버전}__{설명}.sql` | 적용. Flyway 규칙 그대로 |
| `U{버전}__{설명}.sql` | 같은 버전의 V 를 되돌린다. Flyway Community 는 이 파일을 무시하므로 앱의 자체 Flyway 와 같은 폴더에 둬도 된다 |
| `-- lily:irreversible` (V 파일 안 한 줄) | 되돌릴 수 없는 V (contract 단계의 DROP). U 가 없어도 되고, 이 버전을 지나서는 롤백하지 않는다 |
| `R__*.sql` | 지원하지 않는다 (이전 내용으로 되돌릴 수 없다) |

**적용 전 lint** (아직 적용되지 않은 V 만 본다. 한 건이라도 걸리면 400, DB 는 건드리지 않는다)

| 규칙 | 이유 |
|---|---|
| V 마다 U 가 있거나 `lily:irreversible` (첫 릴리스는 제외) | 롤백 가능성을 배포 시점에 보장. 첫 릴리스는 되돌아갈 이전 릴리스가 없고, 그 V 는 이후 롤백 범위에 들어가지 않는다. 첫 릴리스가 Ready 전에 실패하면 U 가 없는 스키마는 남는다 |
| `RENAME`, 컬럼 타입 변경(`ALTER COLUMN .. TYPE`, `MODIFY`, `CHANGE`) 금지 | 전환 전까지 이전 슬롯이 새 스키마 위에서 돈다. 이전 코드의 쿼리가 깨진다 |
| `ADD COLUMN .. NOT NULL` 은 `DEFAULT` 필수, `SET NOT NULL` 금지 | 이전 슬롯의 INSERT 가 깨진다 |
| `DROP TABLE/COLUMN`, `TRUNCATE` 는 `lily:irreversible` 에서만 | 데이터가 사라져 U 로 복구할 수 없다 |

이름 변경은 expand/contract 로 나눈다. (1) 새 컬럼 추가 + 백필, U 는 새 컬럼 삭제 (2) 코드 전환 (3) 다음 배포에서 옛 컬럼 DROP (`lily:irreversible`).
데이터를 옮기는 V 의 U 는 **역방향 백필**을 포함해야 한다. 그러지 않으면 롤백 뒤 이전 코드가 전환 이후의 변경을 보지 못한다.

## 2. 배포 (MIGRATION 단계)

배포 요청의 `migrations` (파일명 → SQL) 가 비어 있지 않고 `database` 가 있을 때만 동작한다. 없으면 이전과 같다 (앱 자체 Flyway).

1. **파싱** — 파일명, V/U 짝, 합계 900KiB 이하. 위반은 400
2. **적용 대상 계산** — DB 의 `flyway_schema_history` (성공 행) 에 없는 V
3. **lint** — 1 절 규칙
4. **dry-run** (PostgreSQL) — 한 트랜잭션 안에서 대상 V 적용 → U 역순 → V 재적용 후 `ROLLBACK`.
   U 가 실제로 V 를 되돌리는지 배포 전에 확인한다. MySQL 은 DDL 이 자동 커밋이라 건너뛰고, 버전마다 적용한다.
   한 파일이 중간에서 실패하면 history 에 없어도 그 버전의 U 를 시도한다. 앞 버전에 기록된 것은 기존처럼 되돌린다
5. **migrate** — lily-cicd 안에서 Flyway (spring-boot 3.4 관리 버전, 10.20) 로 V 만 적용.
   `placeholderReplacement=false` (dry-run 과 같은 SQL), 세션 `lock_timeout 5s` (DDL 락 대기로 서비스가 멈추는 대신 실패)
6. **보관** — 스크립트 전체를 ConfigMap `{app}-{slot}-schema` 에, 스키마 버전 등을 슬롯 Deployment 어노테이션에 남긴다
7. 앱에는 `SPRING_FLYWAY_ENABLED=false` 를 넣는다. 스키마는 플랫폼만 바꾼다

**migrate 이후 실패** — 슬롯 적용이나 Ready 대기에서 실패하면 새 Deployment 를 지우고, 이번에 적용한 버전을 U 로 되돌린다.
되돌리지 못하면 (irreversible, U 실패) 실패 이유에 스키마가 남은 버전을 붙인다. 트래픽을 옮긴 뒤의 실패는 되돌리지 않는다.
프로세스가 트래픽을 옮기기 전에 죽으면, 다음 cicd 가 진행 맥박이 멈춘 뒤 같은 U 로 되돌린다. 트래픽이 이미 새 슬롯이면 스키마는 그대로 둔다.

### Job 대신 lily-cicd 안에서 실행하는 이유
SQL 은 어디서 돌든 같은 DB 에 같은 프로젝트 계정으로 실행된다. 격리로 얻는 것이 없고, Job 은 Pod 스케줄링과 이미지 pull 로 배포마다 10~20초를 더하며 ConfigMap/Secret/RBAC 가 늘어난다.
lily-cicd 는 worker 에서 돌고, RDS 보안 그룹은 worker 를 이미 허용한다.

## 3. 릴리스 기록

클러스터 안에 둔다 (lily-cicd 는 AWS 권한이 없는 worker 에서 돈다).

| 위치 | 키 | 값 |
|---|---|---|
| Deployment `{app}-{slot}` 어노테이션 | `lily.io/deployed-at` | 배포 시각 (ISO-8601). 롤백 방향 판단 |
| | `lily.io/schema-version` | 이 릴리스가 끝났을 때의 스키마 버전. 플랫폼이 마이그레이션을 맡은 앱만 |
| | `lily.io/database` | `postgres` / `mysql` |
| ConfigMap `{app}-{slot}-schema` | 파일명 | 이 릴리스 커밋의 V/U 전체. 롤백할 때 필요한 U 는 **현재** 릴리스에만 있다 |
| Lease `lily-lock-{app}` | holder | 배포·롤백 직렬화. 잡은 쪽이 5초마다 갱신하고, 갱신이 45초 멈추면 만료로 보고 가져간다 |

## 4. 롤백

`POST /api/deployments/{appName}/rollback` `{"namespace": null, "appOnly": false}` — blue-green 과 canary 가 지원한다. 범위는 직전 릴리스(N-1) 한 단계다.

**사전 검사** (하나라도 실패하면 409, 아무것도 바꾸지 않는다)
- 이전 슬롯 Deployment 가 있고 `deployed-at` 이 현재 슬롯보다 과거 (이미 롤백한 상태에서 다시 롤백하면 앞으로 가게 되므로 거부)
- `appOnly=false` 이고 현재 릴리스에 `schema-version` 이 있으면: 이전 릴리스에도 `schema-version` 이 있고,
  (이전 버전, 현재 DB 버전] 의 모든 버전에 U 가 있으며 `lily:irreversible` 이 없어야 한다

**실행 순서**
1. 이전 슬롯 replica 1 → Ready 대기 (lint 덕분에 새 스키마 위에서도 뜬다)
2. Service selector 를 이전 슬롯으로 — 여기까지가 앱 롤백
3. 백업 (PostgreSQL) — 스키마 `lily_bak_{시각}` 에 테이블 전체 복사. 최근 3개만 남긴다
4. U 를 버전 역순으로 실행하고 history 행을 지운다. PostgreSQL 은 한 트랜잭션 (실패하면 전부 취소), MySQL 은 버전마다
5. 현재 슬롯 replica 0

4가 실패해도 앱 롤백은 유지하고 결과 `status=PARTIAL` 과 로그로 알린다. 새 스키마는 lint 로 이전 코드와 호환되므로 서비스는 계속된다.
history 행을 지웠으므로 고친 V 를 같은 버전 번호로 다시 배포할 수 있다.

## 5. API

| | |
|---|---|
| `POST /api/deployments` | 기존 + `migrations: {"V3__add_col.sql": "...", "U3__add_col.sql": "..."}` |
| `POST /api/deployments/{app}/rollback` | 위 4 절. 200 `ROLLED_BACK` / `PARTIAL`, 409 거부·잠금, 400 미지원 전략 |
| `GET /api/deployments/{app}` | 활성 슬롯, 슬롯별 이미지·스키마 버전·배포 시각, 롤백 가능 여부와 이유 |

결과 DTO 에 `schemaVersion` 이 추가된다.

## 6. 한계

- Java 기반 마이그레이션, callback, placeholder 는 쓰지 않는다. 필요하면 lily-builder 에서 `migrate=false` 로 앱 자체 Flyway 를 쓴다 (롤백은 앱만)
- `CREATE INDEX CONCURRENTLY` 처럼 트랜잭션 밖에서만 되는 문장은 dry-run 에서 실패한다
- 롤백은 N-1 한 단계. 더 이전은 예전 이미지 재배포로 한다
- 배포가 Ready 전에 실패하면 기존 동작대로 target 슬롯 Deployment 를 지운다. target 은 직전 릴리스가 있던 슬롯이라, 실패한 배포 뒤에는 롤백할 이전 슬롯이 없다 (스키마는 자동으로 되돌린다)
- MySQL 은 dry-run 과 백업이 없고, U 가 중간에 실패하면 일부만 되돌아간다
- canary 전략도 롤백한다. 100% 이후 이전 트랙을 replica 0 으로 남기고, 롤백은 그 Deployment 를 다시 띄운 뒤 본 Service 의 track 을 되돌린다

## 7. 무중단 스키마 변경 (pgroll)

1~6 절의 Flyway 방식은 이전 슬롯이 새 스키마 위에서 돌기 때문에 이름·타입 변경과 기본값 없는 `NOT NULL` 을 막는다.
pgroll 방식은 같은 DB 에 스키마 버전 두 개를 동시에 두고, 두 버전의 쓰기를 DB 트리거가 서로 맞춘다.
그래서 그런 변경도 한 번의 배포로 하고, 전환 뒤에도 행을 잃지 않고 앱과 스키마를 함께 되돌린다. PostgreSQL 만 쓴다.

```text
배포   이전 active complete → start → 새 슬롯은 public_{새 이름} 으로 접속 → Ready → 판정 → 전환 → 롤백 창 열림
실패   트래픽을 옮기기 전이면 rollback (새 버전 스키마·임시 컬럼·트리거만 지운다). canary 가중치를 올리다 실패해도 같다
롤백   (창 안) 이전 슬롯 복구 → selector 전환 → 현재 슬롯 0 → Pod 종료 확인 → rollback
종료   창이 지나거나 다음 배포가 오면 complete (옛 컬럼·이전 버전 스키마 삭제)
```

### 7.1 파일 규칙

앱 레포 `{rootDir}/db/pgroll/{번호}_{소문자_설명}.yaml` (또는 `.json`). lily-builder 는 이 폴더에 파일이 있으면 SQL 대신 이 파일들을 `migrations` 로 보낸다.
파일명(확장자 제외)이 마이그레이션 이름이고, 앱은 `public_{이름}` 스키마로 접속한다. 이름은 56자 이하다 (스키마 이름 63바이트).

```yaml
# db/pgroll/02_subject_and_slug.yaml
operations:
  - add_column:
      table: posts
      up: "lower(replace(subject, ' ', '-'))"   # 이전 버전이 쓴 행을 새 컬럼으로 옮기는 식
      column: { name: slug, type: varchar(220), nullable: true }
  - rename_column:
      table: posts
      from: title
      to: subject
```

- `up` 은 이전 버전의 쓰기를 새 컬럼으로, `down` 은 새 버전의 쓰기를 옛 컬럼으로 옮긴다. 같은 마이그레이션에서 이름도 바꾸면 식 안의 컬럼은 새 이름으로 쓴다
- 적용 전 검사 (`PgrollLinter`, 위반은 400 이고 DB 는 바뀌지 않는다). Flyway lint 와 달리 이름·타입 변경은 막지 않는다
  - `sql` 연산은 받지 않는다. 버전 스키마 없이 실제 테이블에 바로 적용돼 이전 버전도 바뀐다
  - `add_column` 의 NOT NULL 컬럼은 `default` 가 있어야 한다. pgroll 은 `nullable` 을 쓰지 않으면 NOT NULL 이다. default 가 없으면 start 가 NOT NULL 검사 제약을 up 트리거보다 먼저 걸어서, 그 사이 이전 버전의 INSERT 가 실패한다 (실측 980건 중 1건). `nullable: true` 로 두거나 default 를 준다
  - `alter_column` 에 `up` 이나 `type` 이 있으면 `down` 도 있어야 한다. 없으면 롤백할 때 새 버전이 쓴 값을 잃는다
- 앱의 키 생성은 IDENTITY 나 serial 기본값을 쓴다. 버전 스키마에는 뷰만 있어서 스키마 없이 부르는 `nextval('..._seq')` 은 찾지 못한다
- 이전 슬롯이 떠 있으면 배포마다 새 마이그레이션은 하나다. 둘 이상이면 400 (앞의 것을 complete 하면 이전 슬롯의 스키마가 사라진다)
- pgroll 로 관리하는 DB 에 SQL 마이그레이션을 보내면 400. 마이그레이션 파일이 없는 커밋은 최신 버전 스키마로 접속한다

### 7.2 배포

1. DB 모듈에 `POST /api/databases/{id}/pgroll` 을 부른다. init 은 이벤트 트리거를 만들어서 관리자 계정이 필요하다. lily-db-provisioner 가 init 하고 프로젝트 계정에 `pgroll` 스키마 권한을 준다. 여러 번 불러도 된다
2. 진행 중(active)인 이전 마이그레이션이 있으면 complete 한다. 이전 배포의 롤백 창이 여기서 닫힌다
3. 이력 없이 테이블만 있는 DB (Flyway 로 관리하던 앱) 는 `baseline` 으로 현재 스키마를 출발점으로 남긴다
4. 새 마이그레이션을 start 한다. lock timeout 500ms, backfill 1000행씩. 실패하면 pgroll 이 스스로 되돌리고 슬롯은 만들지 않는다
5. 새 슬롯 Secret 에 버전 스키마를 넣는다. `DB_URL`·`SPRING_DATASOURCE_URL` 에 `currentSchema=public_{이름}`, `DATABASE_URL` 에 `options=-c search_path=public_{이름}`, `LILY_DB_SCHEMA`. 앱 자체 Flyway 는 끈다
6. 판정·전환은 전략 그대로다. 블루그린 판정 중이나 canary 가중치를 올리는 동안 두 버전이 같은 DB 에 동시에 쓴다
7. 전환 뒤 그 슬롯에 롤백 창(기본 600초)을 연다

pgroll 트리거는 `search_path` 가 최신 버전 스키마 이름과 정확히 같을 때만 새 버전의 쓰기로 본다. 그래서 `currentSchema` 에는 다른 스키마를 붙이지 않는다.

### 7.3 롤백과 complete

- 롤백 창 안의 `POST /api/deployments/{app}/rollback` 은 앱과 스키마를 함께 되돌린다. 새 버전이 쓴 값은 down 트리거로 옛 컬럼에 이미 들어가 있어서 행이 남는다. 새 버전에서 추가한 컬럼 값만 없어진다
- 스키마를 바꾼 릴리스는 `appOnly` 롤백을 받지 않는다 (409). 코드만 바뀐 릴리스는 앱만 되돌린다
- 창이 지나면 `PgrollCompleter` 가 30초마다 찾아 complete 한다. 창을 바로 닫으려면 `POST /api/deployments/{app}/schema/complete` (200 `COMPLETED`, 열린 창이 없으면 409)
- complete 뒤에는 이전 버전 스키마가 없어서 롤백은 409 다
- canary 가중치를 올리다 실패하면 전략이 새 슬롯과 가중치 입구를 지우고 이전 슬롯으로 트래픽을 되돌린다. 엔진은 새 슬롯이 지워진 것을 확인하고 바로 rollback 한다. 새 슬롯이 남아 있으면(이미 Service 를 옮긴 뒤의 실패) 스키마는 그대로 두고 롤백 API 로 되돌린다
- 트래픽을 옮기기 전에 cicd 가 죽으면 `DeployRecovery` 가 새 슬롯을 지우고 rollback 한다

### 7.4 릴리스 기록

| Deployment `{app}-{slot}` 어노테이션 | 값 |
|---|---|
| `lily.io/schema-engine` | `pgroll` |
| `lily.io/schema-version` | 마이그레이션 이름 |
| `lily.io/pgroll-state` | 이 릴리스가 시작한 마이그레이션의 상태. `active` (롤백 창) / `complete` / `rolled-back` |
| `lily.io/pgroll-complete-after` | 이 시각이 지나면 complete |

진행 상태의 기준은 DB 의 `pgroll.migrations` 다. 어노테이션은 `GET /api/deployments/{app}` 의 롤백 가능 여부에 쓴다.

### 7.5 설정 (`lily.schema.pgroll.*`)

| 키 | 기본값 | |
|---|---|---|
| `sslmode` (`PGROLL_SSLMODE`) | `require` | 로컬 Postgres 컨테이너는 `disable` |
| `lock-timeout-millis` | `500` | DDL 이 잠금을 기다리는 최대 시간 |
| `timeout-seconds` | `300` | 명령 하나 (backfill 포함) |
| `backfill-batch-size` / `backfill-batch-delay` | `1000` / `0s` | |
| `rollback-window-seconds` (`PGROLL_ROLLBACK_WINDOW_SECONDS`) | `600` | |
| `completer-interval-seconds` | `30` | |

CLI 는 이미지에 들어 있다 (pgroll v0.16.3, 체크섬 확인). 테스트는 Gradle `downloadPgroll` 이 받은 CLI 를 Testcontainers PostgreSQL 에 돌린다.

### 7.6 검증 (2026-10-03, 공용 RDS, lily-blog-sample, builder → cicd → provisioner)

| 전략 | 확인한 것 |
|---|---|
| blue-green | `slug NOT NULL` 추가 + `title`→`subject` 배포 → 전환 후 롤백 → 재배포 → complete API. 요청 3,105건 실패 0, 롤백 후 행 누락 0, complete 뒤 롤백 409 |
| canary | 가중치 20→100% 동안 두 버전이 동시에 쓰기 (v1 1,018행, v2 380행). 트리거가 계산한 slug 와 새 버전이 쓴 slug 모두 맞음, 롤백 후 행 누락 0. 요청 1,752건 실패 0 |
