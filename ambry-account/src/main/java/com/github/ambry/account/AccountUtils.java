/*
 * Copyright 2017 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.github.ambry.account;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Utils for Account-related operations.
 */
public class AccountUtils {
  private static final Logger logger = LoggerFactory.getLogger(AccountUtils.class);

  /**
   * Checks if there are duplicate accountId or accountName in the given collection of {@link Account}s.
   *
   * @param accounts A collection of {@link Account}s to check duplication.
   * @return {@code true} if there are duplicated accounts in id or name in the given collection of accounts,
   *                      {@code false} otherwise.
   */
  static boolean hasDuplicateAccountIdOrName(Collection<Account> accounts) {
    if (accounts == null) {
      return false;
    }
    Set<Short> idSet = new HashSet<>();
    Set<String> nameSet = new HashSet<>();
    boolean res = false;
    for (Account account : accounts) {
      if (!idSet.add(account.getId()) || !nameSet.add(account.getName())) {
        logger.debug("Accounts to update have conflicting id or name. Conflicting accountId={} accountName={}",
            account.getId(), account.getName());
        res = true;
        break;
      }
    }
    return res;
  }

  /**
   * Returns {@link Set} of deprecated {@link Container}s ready for deletion from {@link AccountService}.
   * @param accountService {@link AccountService} object.
   * @param containerDeletionRetentionDays Number of days upto which deprecated containers can be marked as ACTIVE.
   * @return {@link Set} of deprecated {@link Container}s.
   */
  public static Set<Container> getDeprecatedContainers(AccountService accountService,
      long containerDeletionRetentionDays) {
    Set<Container> deprecatedContainers = new HashSet<>();
    accountService.getContainersByStatus(Container.ContainerStatus.DELETE_IN_PROGRESS).forEach((container) -> {
      if (container.getDeleteTriggerTime() + TimeUnit.DAYS.toMillis(containerDeletionRetentionDays)
          <= System.currentTimeMillis()) {
        deprecatedContainers.add(container);
      }
    });
    deprecatedContainers.addAll(accountService.getContainersByStatus(Container.ContainerStatus.INACTIVE));
    return deprecatedContainers;
  }
}
