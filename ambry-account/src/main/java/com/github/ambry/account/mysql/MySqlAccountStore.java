/*
 * Copyright 2020 LinkedIn Corp. All rights reserved.
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
package com.github.ambry.account.mysql;

import com.github.ambry.account.Account;
import com.github.ambry.account.Container;
import java.sql.SQLException;
import java.util.Collection;


/**
 * Wrapper class to handle MySql store operations on Account and Container tables
 */
public class MySqlAccountStore {

  private final AccountDao accountDao;
  private final ContainerDao containerDao;
  private final MySqlDataAccessor mySqlDataAccessor;

  public MySqlAccountStore(MySqlUtils.DbEndpoint dbEndpoint) throws SQLException {
    mySqlDataAccessor = new MySqlDataAccessor(dbEndpoint);
    accountDao = new AccountDao(mySqlDataAccessor);
    containerDao = new ContainerDao(mySqlDataAccessor);
  }

  public MySqlDataAccessor getMySqlDataAccessor() {
    return mySqlDataAccessor;
  }

  /**
   * Adds new {@link Account}s to Account table in MySql DB
   * @param accounts collection of {@link Account}s to be inserted
   * @throws SQLException
   */
  public void addAccounts(Collection<Account> accounts) throws SQLException {
    for (Account account : accounts) {
      accountDao.addAccount(account);
    }
  }

  /**
   * Adds new {@link Container}s to Container table in MySql DB
   * @param containers collection of {@link Container}s to be inserted
   * @throws SQLException
   */
  public void addContainers(Collection<Container> containers) throws SQLException {
    for (Container container : containers) {
      containerDao.addContainer(container.getParentAccountId(), container);
    }
  }

  /**
   * Updates existing {@link Account}s in Account table in MySql DB
   * @param accounts collection of {@link Account}s to be updated
   * @throws SQLException
   */
  public void updateAccounts(Collection<Account> accounts) throws SQLException {
    for (Account account : accounts) {
      accountDao.updateAccount(account);
    }
  }

  /**
   * Updates existing {@link Container}s in Container table in MySql DB
   * @param containers collection of {@link Account}s to be updated
   * @throws SQLException
   */
  public void updateContainers(Collection<Container> containers) throws SQLException {
    for (Container container : containers) {
      containerDao.updateContainer(container.getParentAccountId(), container);
    }
  }

  /**
   * Gets all {@link Account}s that have been created or modified since the specified time.
   * @param updatedSince the last modified time used to filter.
   * @return a collection of {@link Account}s
   * @throws SQLException
   */
  public Collection<Account> getNewAccounts(long updatedSince) throws SQLException {
    return accountDao.getNewAccounts(updatedSince);
  }

  /**
   * Gets all {@link Container}s that have been created or modified since the specified time.
   * @param updatedSince the last modified time used to filter.
   * @return a collection of {@link Container}s
   * @throws SQLException
   */
  public Collection<Container> getNewContainers(long updatedSince) throws SQLException {
    return containerDao.getNewContainers(updatedSince);
  }

  /**
   * Gets all {@link Container}s of a given account
   * @param accountId ID of the account
   * @return a collection of {@link Container}s
   * @throws SQLException
   */
  public Collection<Container> getContainersByAccount(short accountId) throws SQLException {
    return containerDao.getContainers(accountId);
  }
}
