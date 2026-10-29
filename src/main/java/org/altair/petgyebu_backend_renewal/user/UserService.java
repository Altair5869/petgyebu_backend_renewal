package org.altair.petgyebu_backend_renewal.user;

import java.util.Set;
import org.altair.petgyebu_backend_renewal.budget.MonthlyBudgetRepository;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.altair.petgyebu_backend_renewal.common.DbErrors;
import org.altair.petgyebu_backend_renewal.expense.ExpenseRepository;
import org.altair.petgyebu_backend_renewal.goal.CategoryGoalRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

@Service
public class UserService {

    private static final Set<String> PET_TYPES = Set.of("CAT", "DOG");
    private static final int WITHDRAW_ATTEMPTS = 3;

    private final UserRepository users;
    private final ExpenseRepository expenses;
    private final CategoryGoalRepository goals;
    private final MonthlyBudgetRepository budgets;
    private final TransactionTemplate tx;

    public UserService(UserRepository users, ExpenseRepository expenses, CategoryGoalRepository goals,
            MonthlyBudgetRepository budgets, TransactionTemplate tx) {
        this.users = users;
        this.expenses = expenses;
        this.goals = goals;
        this.budgets = budgets;
        this.tx = tx;
    }

    // 인증 직후 행이 사라졌다면(동시 탈퇴) 종료된 로그인과 같이 취급한다.
    @Transactional(readOnly = true)
    public User me(long userId) {
        return users.findById(userId).orElseThrow(ApiException::unauthorized);
    }

    // 지출 → 월 예산 → 카테고리 목표 → 사용자 순으로 한 트랜잭션에서 삭제한다. 어느 단계든 실패하면 모두 롤백된다.
    // 목표 PUT이 예산 행을 먼저 잠그므로 같은 순서로 잠가 교착 상태를 피한다.
    // 삭제 도중 같은 사용자의 지출·목표·예산이 끼어들어 FK 위반이 나면 새 트랜잭션으로 처음부터 다시 시도한다.
    public void withdraw(long userId) {
        for (int attempt = 1; ; attempt++) {
            try {
                tx.executeWithoutResult(status -> {
                    expenses.deleteAllOfUser(userId);
                    budgets.deleteAllOfUser(userId);
                    goals.deleteAllOfUser(userId);
                    users.deleteUser(userId);
                });
                return;
            } catch (DataIntegrityViolationException e) {
                if (attempt >= WITHDRAW_ATTEMPTS || !DbErrors.isForeignKeyViolation(e)) {
                    throw e;
                }
            }
        }
    }

    // users.pet_type만 바꾼다. 지출·예산은 건드리지 않는다. 대소문자를 구분하고 null로 되돌리지 않는다.
    @Transactional
    public User selectPet(long userId, JsonNode body) {
        PetRequest request = PetRequest.from(body);
        validatePetType(request.petType());
        if (users.updatePetType(userId, request.petType()) == 0) {
            throw ApiException.unauthorized();
        }
        return me(userId);
    }

    private void validatePetType(String petType) {
        if (!PET_TYPES.contains(petType)) {
            throw ApiException.invalidRequest("petType", "CAT 또는 DOG만 허용합니다.");
        }
    }
}
