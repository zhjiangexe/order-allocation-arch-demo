package com.flowzati.archone.inventory.reservation.infrastructure.config;

import com.flowzati.archone.inventory.reservation.application.exception.StaleStockAllocationProposalException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/** Translates application-level stale planning into the messaging retry protocol at the infrastructure edge. */
@Aspect
@Component
public class AssignmentRetryConflictTranslator {

    @Around("execution(* com.flowzati.archone.inventory.reservation.application.service."
            + "StockOperationAssignmentCoordinator.tryAssign*(..))")
    public Object translate(ProceedingJoinPoint invocation) throws Throwable {
        try {
            return invocation.proceed();
        } catch (StaleStockAllocationProposalException staleProposal) {
            throw new OptimisticLockingFailureException(staleProposal.getMessage(), staleProposal);
        }
    }
}
