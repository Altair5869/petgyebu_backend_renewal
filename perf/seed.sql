-- 성능 측정용 시드. 측정 전용 DB에만 실행한다(개발·운영 DB 금지).
-- 사용: psql -v users=10000 -v months=24 -f perf/seed.sql
-- 사용자 perf-NNNNN 등급: i % 100 = 0 → heavy(월 300건), i % 100 < 10 → mid(월 100건), 그 외 → normal(월 30건)
-- 지출은 날짜순·무작위로 섞어 넣어 여러 사용자의 행이 시간순으로 쌓인 운영 상태에 가깝게 한다.

\set ON_ERROR_STOP on
\if :{?users}
\else
  \set users 10000
\endif
\if :{?months}
\else
  \set months 24
\endif

SELECT setseed(0.42);

TRUNCATE expenses, monthly_budgets, users RESTART IDENTITY;

INSERT INTO users (provider, provider_user_id, pet_type, last_login_time)
SELECT 'KAKAO', 'perf-' || lpad(i::text, 5, '0'), CASE WHEN i % 2 = 0 THEN 'CAT' ELSE 'DOG' END, now()
FROM generate_series(1, :users) i;

CREATE TEMP TABLE perf_months AS
SELECT m::date AS month_start,
       LEAST((m + interval '1 month')::date, ((now() AT TIME ZONE 'Asia/Seoul')::date + 1)) AS month_end
FROM generate_series(
       date_trunc('month', now() AT TIME ZONE 'Asia/Seoul') - (:months - 1) * interval '1 month',
       date_trunc('month', now() AT TIME ZONE 'Asia/Seoul'),
       interval '1 month') m;

INSERT INTO monthly_budgets (user_id, budget_month, amount)
SELECT u.id, pm.month_start, 300000 + (u.id % 8) * 100000
FROM users u CROSS JOIN perf_months pm;

INSERT INTO expenses (user_id, amount, expense_date, category, memo)
SELECT user_id, amount, expense_date, category, memo
FROM (
  SELECT u.id AS user_id,
         1000 + floor(random() * 49) * 1000 AS amount,
         pm.month_start + (k % (pm.month_end - pm.month_start)) AS expense_date,
         (ARRAY['FOOD','CAFE','TRANSPORT','SHOPPING','HEALTH','LIVING','OTHER'])[1 + (k + u.id) % 7] AS category,
         CASE WHEN random() < 0.5 THEN NULL ELSE 'memo ' || k END AS memo
  FROM users u
  CROSS JOIN perf_months pm
  CROSS JOIN LATERAL generate_series(1,
       CASE WHEN u.id % 100 = 0 THEN 300 WHEN u.id % 100 < 10 THEN 100 ELSE 30 END) k
) s
ORDER BY expense_date, random();

VACUUM ANALYZE users;
VACUUM ANALYZE monthly_budgets;
VACUUM ANALYZE expenses;

SELECT (SELECT count(*) FROM users) AS users,
       (SELECT count(*) FROM monthly_budgets) AS budgets,
       (SELECT count(*) FROM expenses) AS expenses,
       pg_size_pretty(pg_total_relation_size('expenses')) AS expenses_size;
