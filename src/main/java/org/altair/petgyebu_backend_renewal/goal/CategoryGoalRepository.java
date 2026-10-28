package org.altair.petgyebu_backend_renewal.goal;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

public interface CategoryGoalRepository extends Repository<CategoryGoal, Long> {

    @Query("SELECT * FROM category_goals WHERE user_id = :userId AND goal_month = :goalMonth")
    List<CategoryGoal> findAll(long userId, LocalDate goalMonth);

    // 동시 PUT도 한 문장 안에서 생성 또는 교체되므로 409·재시도가 필요 없다.
    @Query("""
            INSERT INTO category_goals (user_id, goal_month, category, amount, created_at, updated_at)
            VALUES (:userId, :goalMonth, :category, :amount, :now, :now)
            ON CONFLICT (user_id, goal_month, category)
            DO UPDATE SET amount = EXCLUDED.amount, updated_at = EXCLUDED.updated_at
            RETURNING *
            """)
    CategoryGoal upsert(long userId, LocalDate goalMonth, String category, long amount, OffsetDateTime now);

    @Modifying
    @Query("DELETE FROM category_goals WHERE user_id = :userId AND goal_month = :goalMonth AND category = :category")
    int delete(long userId, LocalDate goalMonth, String category);

    // 탈퇴 전용. 사용자의 카테고리 목표 전체를 지운다.
    @Modifying
    @Query("DELETE FROM category_goals WHERE user_id = :userId")
    int deleteAllOfUser(long userId);
}
