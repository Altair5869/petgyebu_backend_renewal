-- Repository 쿼리의 실행 계획. 측정 전용 DB에서만 실행한다.
-- 사용: psql -v uid=1 -v month_offset=0 -f perf/explain.sql   (month_offset: 0 이번 달, -6 여섯 달 전)
-- 쿼리 본문은 ExpenseRepository·MonthlyBudgetRepository·UserRepository의 @Query와 같게 유지한다.

\set ON_ERROR_STOP on
\if :{?uid}
\else
  \set uid 1
\endif
\if :{?month_offset}
\else
  \set month_offset 0
\endif

SELECT (date_trunc('month', now() AT TIME ZONE 'Asia/Seoul') + :month_offset * interval '1 month')::date AS m_from \gset
SELECT (:'m_from'::date + interval '1 month')::date AS m_to \gset
SELECT expense_date AS c_date, id AS c_id FROM expenses
WHERE user_id = :uid AND expense_date >= :'m_from' AND expense_date < :'m_to'
ORDER BY expense_date DESC, id DESC OFFSET 19 LIMIT 1 \gset

\echo == findAmounts
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON)
SELECT category, amount FROM expenses
WHERE user_id = :uid AND expense_date >= :'m_from' AND expense_date < :'m_to';

-- findDayAmounts 범위: [least(이번 달 1일, 지난주 월요일), 오늘+1), 서울 기준
SELECT least(date_trunc('month', now() AT TIME ZONE 'Asia/Seoul')::date,
             (date_trunc('week', (now() AT TIME ZONE 'Asia/Seoul')::date) - interval '7 days')::date) AS d_from \gset
SELECT ((now() AT TIME ZONE 'Asia/Seoul')::date + 1) AS d_to \gset

\echo == findDayAmounts
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON)
SELECT expense_date, category, amount FROM expenses
WHERE user_id = :uid AND expense_date >= :'d_from' AND expense_date < :'d_to';

\echo == findFirstPage (limit 21)
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON)
SELECT * FROM expenses
WHERE user_id = :uid AND expense_date >= :'m_from' AND expense_date < :'m_to'
ORDER BY expense_date DESC, id DESC
LIMIT 21;

\echo == findPageAfter (limit 21)
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON)
SELECT * FROM expenses
WHERE user_id = :uid AND expense_date >= :'m_from' AND expense_date < :'m_to'
  AND (expense_date, id) < (:'c_date'::date, :c_id)
ORDER BY expense_date DESC, id DESC
LIMIT 21;

\echo == monthlyBudget find
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON)
SELECT * FROM monthly_budgets WHERE user_id = :uid AND budget_month = :'m_from';

\echo == findFirstPageInCategory (limit 21)
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON)
SELECT * FROM expenses
WHERE user_id = :uid AND expense_date >= :'m_from' AND expense_date < :'m_to'
  AND category = 'CAFE'
ORDER BY expense_date DESC, id DESC
LIMIT 21;

\echo == categoryGoal findAll
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON)
SELECT * FROM category_goals WHERE user_id = :uid AND goal_month = :'m_from';

\echo == hasActiveSession
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON)
SELECT EXISTS (SELECT 1 FROM users WHERE id = :uid AND active_session_id = gen_random_uuid());
