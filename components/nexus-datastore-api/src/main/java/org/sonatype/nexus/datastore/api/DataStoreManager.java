/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.datastore.api;

import java.util.Optional;
import java.util.function.Supplier;

import org.sonatype.goodies.lifecycle.Lifecycle;

/**
 * {@link DataStore} manager.
 *
 * @since 3.next
 */
public interface DataStoreManager
    extends Lifecycle
{
  String CONFIG_DATASTORE_NAME = "config";

  String CONTENT_DATASTORE_NAME = "content";

  /**
   * Browse existing data stores.
   */
  Iterable<DataStore<?>> browse();

  /**
   * Create a new data store.
   */
  DataStore<?> create(DataStoreConfiguration configuration) throws Exception;

  /**
   * Update an existing data store.
   */
  DataStore<?> update(DataStoreConfiguration configuration) throws Exception;

  /**
   * Lookup a data store by name.
   */
  Optional<DataStore<?>> get(String storeName);

  /**
   * Delete a data store by name.
   */
  boolean delete(String storeName) throws Exception;

  /**
   * @return {@code true} if the named data store already exists. Check is case-insensitive.
   */
  boolean exists(String storeName);

  /**
   * @return {@code true} if the named data store holds content metadata.
   */
  default boolean isContentStore(String storeName) {
    return !CONFIG_DATASTORE_NAME.equalsIgnoreCase(storeName);
  }

  /**
   * @return supplier of sessions from the named data store.
   */
  default Supplier<DataSession<?>> asSessionSupplier(String storeName) {
    return () -> get(storeName).orElseThrow(() -> new DataStoreNotFoundException(storeName)).openSession();
  }
}
