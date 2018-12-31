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
package org.sonatype.nexus.repository.maintenance.internal;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.function.BooleanSupplier;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.browse.BrowseNodeConfiguration;
import org.sonatype.nexus.repository.security.ContentPermissionChecker;
import org.sonatype.nexus.repository.security.VariableResolverAdapterManager;
import org.sonatype.nexus.repository.storage.Asset;
import org.sonatype.nexus.repository.storage.AssetStore;
import org.sonatype.nexus.repository.storage.BrowseNode;
import org.sonatype.nexus.repository.storage.BrowseNodeStore;
import org.sonatype.nexus.repository.storage.Component;
import org.sonatype.nexus.repository.storage.ComponentMaintenance;
import org.sonatype.nexus.repository.storage.ComponentStore;
import org.sonatype.nexus.repository.storage.StorageFacet;
import org.sonatype.nexus.repository.storage.StorageTx;
import org.sonatype.nexus.security.BreadActions;

import org.joda.time.DateTime;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @since 3.next
 */
@Named
@Singleton
public class DeleteFolderServiceImpl
    extends ComponentSupport
    implements DeleteFolderService
{
  private final BrowseNodeStore browseNodeStore;

  private final BrowseNodeConfiguration configuration;

  private final AssetStore assetStore;

  private final ComponentStore componentStore;

  private final ContentPermissionChecker contentPermissionChecker;

  private final VariableResolverAdapterManager variableResolverAdapterManager;

  @Inject
  public DeleteFolderServiceImpl(final BrowseNodeStore browseNodeStore,
                                 final BrowseNodeConfiguration configuration,
                                 final AssetStore assetStore,
                                 final ComponentStore componentStore,
                                 final ContentPermissionChecker contentPermissionChecker,
                                 final VariableResolverAdapterManager variableResolverAdapterManager)
  {
    this.browseNodeStore = checkNotNull(browseNodeStore);
    this.configuration = checkNotNull(configuration);
    this.assetStore = checkNotNull(assetStore);
    this.componentStore = checkNotNull(componentStore);
    this.contentPermissionChecker = checkNotNull(contentPermissionChecker);
    this.variableResolverAdapterManager = checkNotNull(variableResolverAdapterManager);
  }

  @Override
  public void deleteFolder(final Repository repository,
                           final String treePath,
                           final DateTime timestamp,
                           final BooleanSupplier cancelledCheck)
  {
    ComponentMaintenance componentMaintenance = repository.facet(ComponentMaintenance.class);
    Queue<String> paths = new PriorityQueue<>();
    paths.add(treePath);

    while (!cancelledCheck.getAsBoolean() && !paths.isEmpty()) {
      String basePath = paths.poll();
      List<String> path = Arrays.asList(basePath.split("/"));
      Iterable<BrowseNode> nodes = browseNodeStore.getByPath(repository, path, configuration.getMaxNodes(), null);
      Iterator<BrowseNode> nodeIterator = nodes.iterator();
      Set<EntityId> components = new HashSet<>();

      while (!cancelledCheck.getAsBoolean() && nodeIterator.hasNext()) {
        BrowseNode node = nodeIterator.next();

        if (!node.isLeaf()) {
          paths.offer(basePath + "/" + node.getName());
        }

        if (node.getAssetId() != null) {
          deleteAsset(repository, node.getAssetId(), timestamp, componentMaintenance).ifPresent(components::add);
        }

        if (node.getComponentId() != null) {
          components.add(node.getComponentId());
        }
      }

      Iterator<EntityId> componentIterator = components.iterator();
      while (!cancelledCheck.getAsBoolean() && componentIterator.hasNext()) {
        deleteComponent(repository, componentIterator.next(), componentMaintenance);
      }
    }
  }

  private void deleteComponent(final Repository repository,
                               final EntityId componentId,
                               final ComponentMaintenance componentMaintenance)
  {
    if (canDeleteComponent(repository, componentStore.read(componentId))) {
      try {
        componentMaintenance.deleteComponent(componentId);
      }
      catch (Exception e) {
        log.debug("Failed to delete a component '{}'", componentId);
      }
    }
  }

  /**
   * @return EntityId of associated component if exists
   */
  private Optional<EntityId> deleteAsset(final Repository repository,
                                         final EntityId assetId,
                                         final DateTime timestamp,
                                         final ComponentMaintenance componentMaintenance)
  {
    EntityId componenetId = null;
    Asset asset = assetStore.getById(assetId);
    if (timestamp.isAfter(asset.blobCreated()) && canDeleteAsset(repository, asset)) {
      try {
        componentMaintenance.deleteAsset(assetId);
        componenetId = asset.componentId();
      }
      catch (Exception e) {
        log.error("Failed to delete an asset - skipping.", e);
      }
    }
    return Optional.ofNullable(componenetId);
  }

  private boolean canDeleteAsset(final Repository repository, final Asset asset) {
    return contentPermissionChecker
        .isPermitted(repository.getName(), repository.getFormat().getValue(), BreadActions.DELETE,
            variableResolverAdapterManager.get(repository.getFormat().getValue()).fromAsset(asset));
  }

  private boolean canDeleteComponent(final Repository repository, final Component component) {
    try (StorageTx storageTx = repository.facet(StorageFacet.class).txSupplier().get()) {
      storageTx.begin();
      for (Asset asset : storageTx.browseAssets(component)) {
        if (!canDeleteAsset(repository, asset)) {
          return false;
        }
      }
    }

    return true;
  }
}
