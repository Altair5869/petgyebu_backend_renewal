package org.altair.petgyebu_backend_renewal.home;

import java.time.Clock;
import java.time.YearMonth;
import org.altair.petgyebu_backend_renewal.budget.BudgetUsage;
import org.altair.petgyebu_backend_renewal.budget.MonthlyBudget;
import org.altair.petgyebu_backend_renewal.budget.MonthlyBudgetRepository;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.altair.petgyebu_backend_renewal.common.Months;
import org.altair.petgyebu_backend_renewal.expense.ExpenseRepository;
import org.altair.petgyebu_backend_renewal.expense.MonthlySpending;
import org.altair.petgyebu_backend_renewal.user.User;
import org.altair.petgyebu_backend_renewal.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HomeService {

    public record Home(YearMonth month, String petType, BudgetUsage usage) {
    }

    private final UserRepository users;
    private final MonthlyBudgetRepository budgets;
    private final ExpenseRepository expenses;
    private final Clock clock;

    public HomeService(UserRepository users, MonthlyBudgetRepository budgets, ExpenseRepository expenses,
            Clock clock) {
        this.users = users;
        this.budgets = budgets;
        this.expenses = expenses;
        this.clock = clock;
    }

    // 서울 기준 현재 월 전용. 펫이 먼저, 그다음 현재 월 예산을 요구한다.
    @Transactional(readOnly = true)
    public Home home(long userId) {
        // 인증 직후 행이 사라졌다면(동시 탈퇴) 종료된 로그인과 같이 취급한다.
        User user = users.findById(userId).orElseThrow(ApiException::unauthorized);
        validatePetSelected(user);
        YearMonth month = Months.current(clock);
        Months.Range range = Months.range(month);
        MonthlyBudget budget = requireCurrentBudget(userId, range);
        long spent = MonthlySpending.of(expenses.findAmounts(userId, range.from(), range.to())).total();
        return new Home(month, user.petType(), new BudgetUsage(budget.amount(), spent));
    }

    private static void validatePetSelected(User user) {
        if (user.petType() == null) {
            throw ApiException.petRequired();
        }
    }

    // 새 달 예산을 자동으로 만들지 않으므로 현재 월 예산이 없으면 409다.
    private MonthlyBudget requireCurrentBudget(long userId, Months.Range range) {
        return budgets.find(userId, range.from()).orElseThrow(ApiException::budgetRequired);
    }
}
