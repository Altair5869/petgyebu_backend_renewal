# 배포 절차: Cloud Run + Supabase Free

- 갱신: 2026-10-26
- 기준: [인프라·기술 스택](05-infra-stack.md) 5절의 단계별 배포
- 상태: 저장소 준비(`Dockerfile`, `.github/workflows/deploy.yml`)와 계정 설정 절차. 이 문서만으로 배포 완료를 뜻하지 않는다.

비밀값(DB 비밀번호, JWT 서명 키)은 이 문서·저장소·GitHub 변수·채팅에 적지 않고 Secret Manager에만 둔다. 아래 `<...>`는 각자 값으로 바꾼다.

## 1. Supabase 프로젝트 (사람이 콘솔에서)

1. 새 프로젝트를 **Northeast Asia (Seoul)** 리전에 만든다. 기본 PostgreSQL은 17이며 마이그레이션은 PG 17에서 확인했다.
2. DB 비밀번호는 생성기로 만들고 3절의 Secret Manager에 바로 넣는다.
3. **Data API를 끄고 다시 켜지 않는다.** 앱은 백엔드를 거쳐서만 DB에 접근한다. Supabase는 `public` 테이블에 기본 권한을 주므로 Data API가 켜지면 테이블이 Supabase REST API로 노출될 수 있다.
4. Connect 화면의 **Session pooler** 값을 기록한다.
   - JDBC URL: `jdbc:postgresql://<pooler-host>:5432/postgres?sslmode=require`
   - 사용자: `postgres.<project-ref>`
   - Direct connection은 IPv6 전용이고, Transaction pooler(6543)는 pgJDBC의 서버 측 prepared statement와 충돌하므로 쓰지 않는다.

## 2. GCP 프로젝트와 API (사람이 결제 계정 연결 후 실행)

```sh
PROJECT_ID=<project-id>
REGION=asia-northeast3
GITHUB_REPO=Altair5869/petgyebu_backend_renewal
GITHUB_REPO_ID=1387008289  # gh api repos/$GITHUB_REPO --jq .id
gcloud config set project "$PROJECT_ID"

gcloud services enable run.googleapis.com artifactregistry.googleapis.com \
  secretmanager.googleapis.com iam.googleapis.com iamcredentials.googleapis.com \
  sts.googleapis.com cloudresourcemanager.googleapis.com
PROJECT_NUMBER=$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')

gcloud artifacts repositories create petgyebu --repository-format=docker --location="$REGION"
```

Artifact Registry 무료 저장량을 넘지 않도록 콘솔에서 정리 정책(최근 이미지 몇 개만 유지)을 둔다.

## 3. 서비스 계정과 비밀값

```sh
gcloud iam service-accounts create petgyebu-run     # Cloud Run 실행용
gcloud iam service-accounts create petgyebu-deploy  # GitHub Actions 배포용
RUN_SA=petgyebu-run@$PROJECT_ID.iam.gserviceaccount.com
DEPLOY_SA=petgyebu-deploy@$PROJECT_ID.iam.gserviceaccount.com

# 운영 JWT 키는 새로 만든다. 로컬 키를 재사용하지 않는다.
openssl rand -base64 32 | tr -d '\n' | gcloud secrets create petgyebu-jwt-secret --data-file=-
# Supabase DB 비밀번호: 실행 후 붙여 넣고 Enter, Ctrl-D
gcloud secrets create petgyebu-db-password --data-file=-

for s in petgyebu-jwt-secret petgyebu-db-password; do
  gcloud secrets add-iam-policy-binding "$s" \
    --member="serviceAccount:$RUN_SA" --role=roles/secretmanager.secretAccessor
done

# run.admin은 --allow-unauthenticated(서비스 IAM 정책 변경)에 필요하다.
gcloud projects add-iam-policy-binding "$PROJECT_ID" --member="serviceAccount:$DEPLOY_SA" --role=roles/run.admin
gcloud artifacts repositories add-iam-policy-binding petgyebu --location="$REGION" \
  --member="serviceAccount:$DEPLOY_SA" --role=roles/artifactregistry.writer
gcloud iam service-accounts add-iam-policy-binding "$RUN_SA" \
  --member="serviceAccount:$DEPLOY_SA" --role=roles/iam.serviceAccountUser
```

## 4. GitHub Actions 인증 (Workload Identity Federation)

서비스 계정 키 파일을 만들지 않는다. 이 저장소의 `production` 환경 job만 배포 계정을 쓸 수 있게 제한한다. 저장소 이름은 재사용될 수 있으므로 저장소 ID로 묶는다.

```sh
gcloud iam workload-identity-pools create github --location=global
gcloud iam workload-identity-pools providers create-oidc github \
  --location=global --workload-identity-pool=github \
  --issuer-uri=https://token.actions.githubusercontent.com \
  --attribute-mapping=google.subject=assertion.sub,attribute.repository_id=assertion.repository_id,attribute.environment=assertion.environment \
  --attribute-condition="assertion.repository_id=='$GITHUB_REPO_ID' && assertion.environment=='production'"
gcloud iam service-accounts add-iam-policy-binding "$DEPLOY_SA" --role=roles/iam.workloadIdentityUser \
  --member="principalSet://iam.googleapis.com/projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/github/attribute.repository_id/$GITHUB_REPO_ID"
```

## 5. GitHub `production` 환경 변수

저장소 Settings → Environments에 `production`을 만들고 배포 브랜치를 `main`과 `v*` 태그로 제한한다. 이 규칙은 태그가 어느 커밋에 붙었는지 보지 않으므로, Settings → Rules에서 `v*` 태그 생성을 저장소 관리자로 제한하고 태그는 `main` 커밋에만 붙인다. 아래는 모두 **Variables**(비밀이 아닌 값)다.

| 이름 | 값 |
| --- | --- |
| `GCP_PROJECT_ID` | `<project-id>` |
| `GCP_REGION` | `asia-northeast3` |
| `GCP_WIF_PROVIDER` | `projects/<project-number>/locations/global/workloadIdentityPools/github/providers/github` |
| `GCP_DEPLOY_SA` | `petgyebu-deploy@<project-id>.iam.gserviceaccount.com` |
| `GCP_RUNTIME_SA` | `petgyebu-run@<project-id>.iam.gserviceaccount.com` |
| `SPRING_DATASOURCE_URL` | 1절의 Session pooler JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `postgres.<project-ref>` |
| `PETGYEBU_KAKAO_APP_ID` | 카카오 앱 ID(공개 값) |
| `PETGYEBU_GOOGLE_CLIENT_IDS` | 구글 클라이언트 ID 목록, 쉼표 구분(공개 값). 워크플로가 따옴표로 감싸 넘긴다 |

## 6. 배포와 확인

- `main`에서 `v*` 태그를 push하거나 Actions의 Deploy를 `main`에서 수동 실행한다. 워크플로는 테스트 → 이미지 빌드·push → Cloud Run 배포 순서다.
- 설정: 1 vCPU·1GiB, startup CPU boost, 최소 인스턴스 0·최대 2, 인스턴스당 Hikari 풀 3. 새 리비전으로 넘어가는 동안에는 이전 리비전 인스턴스도 떠 있어 연결이 최대 12개까지 늘 수 있다. Supabase Free의 연결 한도와 `pg_dump` 같은 운영 연결을 합쳐 넘지 않게 한다. 앱이 시작할 때 Flyway가 스키마를 적용한다.
- 배포 뒤 서비스 URL(`https://petgyebu-backend-....run.app`)로 확인한다.
  - 토큰 없이 `GET /api/v1/home` → 401
  - 엉터리 credential로 `POST /api/v1/auth/login/kakao`·`google` → 401 `SOCIAL_LOGIN_FAILED`(503이면 공급자 ID 설정 누락)
  - 앱 운영 빌드의 API 주소를 서비스 URL로 바꿔 로그인 → 펫 → 예산 → 홈을 확인한다.
- 되돌리기: Cloud Run 콘솔에서 이전 리비전으로 트래픽을 옮긴다. 마이그레이션이 추가된 배포는 이전 리비전과 스키마 호환을 먼저 확인한다.

## 7. 백업

Supabase Free의 백업에 기대지 않는다. 7일 동안 활동이 없으면 프로젝트가 일시정지되므로 출시 전 대기 기간에는 대시보드에서 상태를 확인한다. 운영 DB를 주기적으로 받아 안전한 곳에 보관한다(PG 17 클라이언트 필요).

```sh
pg_dump "postgresql://postgres.<project-ref>@<pooler-host>:5432/postgres?sslmode=require" \
  --format=custom --no-owner --file="petgyebu-$(date +%Y%m%d).dump"
```

비밀번호는 실행할 때 입력하고 덤프 파일은 저장소에 넣지 않는다. 덤프에는 개인정보가 있으므로 보관 기간을 개인정보처리방침과 맞춘다.
