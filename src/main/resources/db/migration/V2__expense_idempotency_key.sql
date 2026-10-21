ALTER TABLE expenses ADD COLUMN idempotency_key UUID;

CREATE UNIQUE INDEX uq_expenses_user_idempotency_key
  ON expenses (user_id, idempotency_key)
  WHERE idempotency_key IS NOT NULL;
