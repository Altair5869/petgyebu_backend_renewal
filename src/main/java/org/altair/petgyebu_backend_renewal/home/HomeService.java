package org.altair.petgyebu_backend_renewal.home;

import java.time.Clock;
import java.time.YearMonth;
import org.altair.petgyebu_backend_renewal.budget.BudgetUsage;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.altair.petgyebu_backend_renewal.common.Months;
import org.altair.petgyebu_backend_renewal.expense.ExpenseRepository;
import org.altair.petgyebu_backend_renewal.expense.MonthlySpending;
import org.altair.petgyebu_backend_renewal.user.PetAndBudget;
import org.altair.petgyebu_backend_renewal.user.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class HomeService {

    public record Home(YearMonth month, String petType, BudgetUsage usage) {
    }

    private final UserRepository users;
    private final ExpenseRepository expenses;
    private final Clock clock;

    public HomeService(UserRepository users, ExpenseRepository expenses, Clock clock) {
        this.users = users;
        this.expenses = expenses;
        this.clock = clock;
    }

    // 서울 기준 현재 월 전용. 펫과 현재 월 예산을 한 쿼리로 읽고, 펫이 먼저, 그다음 예산을 요구한다.
    // 읽기 전용 트랜잭션을 두지 않는다. READ COMMITTED에서는 문장마다 새 스냅숏을 보므로
    // 트랜잭션으로 묶어도 두 SELECT 사이의 일관성은 생기지 않고 BEGIN/COMMIT 왕복만 늘어난다.
    public Home home(long userId) {
        YearMonth month = Months.current(clock);
        Months.Range range = Months.range(month);
        // 인증 직후 행이 사라졌다면(동시 탈퇴) 종료된 로그인과 같이 취급한다.
        PetAndBudget row = users.findPetAndBudget(userId, range.from()).orElseThrow(ApiException::unauthorized);
        if (row.petType() == null) {
            throw ApiException.petRequired();
        }
        // 새 달 예산을 자동으로 만들지 않으므로 현재 월 예산이 없으면 409다.
        if (row.budgetAmount() == null) {
            throw ApiException.budgetRequired();
        }
        long spent = MonthlySpending.of(expenses.findAmounts(userId, range.from(), range.to())).total();
        return new Home(month, row.petType(), new BudgetUsage(row.budgetAmount(), spent));
    }
}
