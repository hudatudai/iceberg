/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.iceberg.ManifestEntry.Status;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Iterables;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Sets;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ParameterizedTestExtension.class)
public class TestFastAppend extends TestBase {

  @TestTemplate
  public void testAddManyFiles() {
    assertThat(listManifestFiles()).as("Table should start empty").isEmpty();

    List<DataFile> dataFiles = Lists.newArrayList();

    for (int ordinal = 0; ordinal < 2 * SnapshotProducer.MIN_FILE_GROUP_SIZE; ordinal++) {
      StructLike partition = TestHelpers.Row.of(ordinal % 2);
      DataFile dataFile = FileGenerationUtil.generateDataFile(table, partition);
      dataFiles.add(dataFile);
    }

    AppendFiles append = table.newFastAppend();
    dataFiles.forEach(append::appendFile);
    append.commit();

    validateTableFiles(table, dataFiles);
  }

  @TestTemplate
  public void testEmptyTableFastAppendFilesWithDifferentSpecs() {
    assertThat(listManifestFiles()).as("Table should start empty").isEmpty();

    TableMetadata base = readMetadata();
    assertThat(base.currentSnapshot()).as("Should not have a current snapshot").isNull();
    assertThat(base.lastSequenceNumber()).as("Last sequence number should be 0").isEqualTo(0);

    table.updateSpec().addField("id").commit();
    PartitionSpec newSpec = table.spec();

    assertThat(table.specs()).as("Table should have 2 specs").hasSize(2);

    DataFile fileNewSpec =
        DataFiles.builder(newSpec)
            .withPath("/path/to/data-b.parquet")
            .withPartitionPath("data_bucket=0/id=0")
            .withFileSizeInBytes(10)
            .withRecordCount(1)
            .build();

    Snapshot committedSnapshot =
        commit(
            table,
            table.newFastAppend().appendFile(FILE_A).appendFile(fileNewSpec),
            SnapshotRef.MAIN_BRANCH);

    assertThat(committedSnapshot).as("Should create a snapshot").isNotNull();
    V1Assert.assertEquals(
        "Last sequence number should be 0", 0, table.ops().current().lastSequenceNumber());
    V2Assert.assertEquals(
        "Last sequence number should be 1", 1, table.ops().current().lastSequenceNumber());

    assertThat(committedSnapshot.allManifests(table.io()))
        .as("Should create 2 manifests for initial write, 1 manifest per spec")
        .hasSize(2);

    long snapshotId = committedSnapshot.snapshotId();

    ImmutableMap<Integer, DataFile> expectedFileBySpec =
        ImmutableMap.of(SPEC.specId(), FILE_A, newSpec.specId(), fileNewSpec);

    expectedFileBySpec.forEach(
        (specId, expectedDataFile) -> {
          ManifestFile manifestFileForSpecId =
              committedSnapshot.allManifests(table.io()).stream()
                  .filter(m -> Objects.equals(m.partitionSpecId(), specId))
                  .findAny()
                  .get();

          validateManifest(
              manifestFileForSpecId,
              dataSeqs(1L),
              fileSeqs(1L),
              ids(snapshotId),
              files(expectedDataFile),
              statuses(Status.ADDED));
        });
  }

  @TestTemplate
  public void appendNullFile() {
    assertThatThrownBy(() -> table.newFastAppend().appendFile(null).commit())
        .isInstanceOf(NullPointerException.class)
        .hasMessage("Invalid data file: null");
  }

  @TestTemplate
  public void testEmptyTableAppend() {
    assertThat(listManifestFiles()).isEmpty();

    TableMetadata base = readMetadata();
    assertThat(base.currentSnapshot()).isNull();
    assertThat(base.lastSequenceNumber()).isEqualTo(0);

    table.newFastAppend().appendFile(FILE_A).appendFile(FILE_B).commit();

    Snapshot snap = table.currentSnapshot();

    validateSnapshot(base.currentSnapshot(), snap, 1, FILE_A, FILE_B);

    V2Assert.assertEquals("Snapshot sequence number should be 1", 1, snap.sequenceNumber());
    V2Assert.assertEquals(
        "Last sequence number should be 1", 1, readMetadata().lastSequenceNumber());

    V1Assert.assertEquals(
        "Table should end with last-sequence-number 0", 0, base.lastSequenceNumber());
  }

  @TestTemplate
  public void testEmptyTableAppendManifest() throws IOException {
    assertThat(listManifestFiles()).isEmpty();

    TableMetadata base = readMetadata();
    assertThat(base.currentSnapshot()).isNull();
    assertThat(base.lastSequenceNumber()).isEqualTo(0);

    ManifestFile manifest = writeManifest(FILE_A, FILE_B);
    table.newFastAppend().appendManifest(manifest).commit();

    Snapshot snap = table.currentSnapshot();

    validateSnapshot(base.currentSnapshot(), snap, 1, FILE_A, FILE_B);

    ManifestFile committedManifest = Iterables.getOnlyElement(snap.allManifests(FILE_IO));
    if (formatVersion == 1) {
      assertThat(committedManifest.path()).isNotEqualTo(manifest.path());
    } else {
      assertThat(committedManifest.path()).isEqualTo(manifest.path());
    }

    // validate that the metadata summary is correct when using appendManifest
    assertThat(snap.summary()).containsEntry("added-data-files", "2");

    V2Assert.assertEquals("Snapshot sequence number should be 1", 1, snap.sequenceNumber());
    V2Assert.assertEquals(
        "Last sequence number should be 1", 1, readMetadata().lastSequenceNumber());

    V1Assert.assertEquals(
        "Table should end with last-sequence-number 0", 0, base.lastSequenceNumber());
  }

  @TestTemplate
  public void testEmptyTableAppendFilesAndManifest() throws IOException {
    assertThat(listManifestFiles()).isEmpty();

    TableMetadata base = readMetadata();
    assertThat(base.currentSnapshot()).isNull();
    assertThat(base.lastSequenceNumber()).isEqualTo(0);

    ManifestFile manifest = writeManifest(FILE_A, FILE_B);
    table.newFastAppend().appendFile(FILE_C).appendFile(FILE_D).appendManifest(manifest).commit();

    Snapshot snap = table.currentSnapshot();

    long commitId = snap.snapshotId();

    validateManifest(
        snap.allManifests(FILE_IO).get(0),
        dataSeqs(1L, 1L),
        fileSeqs(1L, 1L),
        ids(commitId, commitId),
        files(FILE_C, FILE_D));
    validateManifest(
        snap.allManifests(FILE_IO).get(1),
        dataSeqs(1L, 1L),
        fileSeqs(1L, 1L),
        ids(commitId, commitId),
        files(FILE_A, FILE_B));

    if (formatVersion == 1) {
      assertThat(snap.allManifests(FILE_IO).get(1).path()).isNotEqualTo(manifest.path());
    } else {
      assertThat(snap.allManifests(FILE_IO).get(1).path()).isEqualTo(manifest.path());
    }

    V2Assert.assertEquals("Snapshot sequence number should be 1", 1, snap.sequenceNumber());
    V2Assert.assertEquals(
        "Last sequence number should be 1", 1, readMetadata().lastSequenceNumber());

    V1Assert.assertEquals(
        "Table should end with last-sequence-number 0", 0, base.lastSequenceNumber());
  }

  @TestTemplate
  public void testNonEmptyTableAppend() {
    table.newAppend().appendFile(FILE_A).appendFile(FILE_B).commit();

    TableMetadata base = readMetadata();
    assertThat(base.currentSnapshot()).isNotNull();
    List<ManifestFile> v2manifests = base.currentSnapshot().allManifests(FILE_IO);
    assertThat(v2manifests).hasSize(1);

    // prepare a new append
    Snapshot pending = table.newFastAppend().appendFile(FILE_C).appendFile(FILE_D).apply();

    assertThat(pending.snapshotId()).isNotEqualTo(base.currentSnapshot().snapshotId());
    validateSnapshot(base.currentSnapshot(), pending, FILE_C, FILE_D);
  }

  @TestTemplate
  public void testNoMerge() {
    table.newAppend().appendFile(FILE_A).commit();

    table.newFastAppend().appendFile(FILE_B).commit();

    TableMetadata base = readMetadata();
    assertThat(base.currentSnapshot()).isNotNull();
    List<ManifestFile> v3manifests = base.currentSnapshot().allManifests(FILE_IO);
    assertThat(v3manifests).hasSize(2);

    // prepare a new append
    Snapshot pending = table.newFastAppend().appendFile(FILE_C).appendFile(FILE_D).apply();

    Set<Long> ids = Sets.newHashSet();
    for (Snapshot snapshot : base.snapshots()) {
      ids.add(snapshot.snapshotId());
    }
    ids.add(pending.snapshotId());
    assertThat(ids).hasSize(3);

    validateSnapshot(base.currentSnapshot(), pending, FILE_C, FILE_D);
  }

  @TestTemplate
  public void testRefreshBeforeApply() {
    // load a new copy of the table that will not be refreshed by the commit
    Table stale = load();

    table.newAppend().appendFile(FILE_A).commit();

    TableMetadata base = readMetadata();
    assertThat(base.currentSnapshot()).isNotNull();
    List<ManifestFile> v2manifests = base.currentSnapshot().allManifests(FILE_IO);
    assertThat(v2manifests).hasSize(1);

    // commit from the stale table
    AppendFiles append = stale.newFastAppend().appendFile(FILE_D);
    Snapshot pending = append.apply();

    // table should have been refreshed before applying the changes
    validateSnapshot(base.currentSnapshot(), pending, FILE_D);
  }

  @TestTemplate
  public void testRefreshBeforeCommit() {
    // commit from the stale table
    AppendFiles append = table.newFastAppend().appendFile(FILE_D);
    Snapshot pending = append.apply();

    validateSnapshot(null, pending, FILE_D);

    table.newAppend().appendFile(FILE_A).commit();

    TableMetadata base = readMetadata();
    assertThat(base.currentSnapshot()).isNotNull();
    List<ManifestFile> v2manifests = base.currentSnapshot().allManifests(FILE_IO);
    assertThat(v2manifests).hasSize(1);

    append.commit();

    TableMetadata committed = readMetadata();

    // apply was called before the conflicting commit, but the commit was still consistent
    validateSnapshot(base.currentSnapshot(), committed.currentSnapshot(), FILE_D);

    List<ManifestFile> committedManifests =
        Lists.newArrayList(committed.currentSnapshot().allManifests(FILE_IO));
    committedManifests.removeAll(base.currentSnapshot().allManifests(FILE_IO));
    assertThat(committedManifests.get(0)).isEqualTo(pending.allManifests(FILE_IO).get(0));
  }

  @TestTemplate
  public void testFailure() {
    // inject 5 failures
    TestTables.TestTableOperations ops = table.ops();
    ops.failCommits(5);

    AppendFiles append = table.newFastAppend().appendFile(FILE_B);
    Snapshot pending = append.apply();
    ManifestFile newManifest = pending.allManifests(FILE_IO).get(0);
    assertThat(new File(newManifest.path())).exists();

    assertThatThrownBy(append::commit)
        .isInstanceOf(CommitFailedException.class)
        .hasMessage("Injected failure");

    assertThat(new File(newManifest.path())).doesNotExist();
  }

  @TestTemplate
  public void testIncreaseNumRetries() {
    TestTables.TestTableOperations ops = table.ops();
    ops.failCommits(TableProperties.COMMIT_NUM_RETRIES_DEFAULT + 1);

    AppendFiles append = table.newFastAppend().appendFile(FILE_B);

    // Default number of retries results in a failed commit
    assertThatThrownBy(append::commit)
        .isInstanceOf(CommitFailedException.class)
        .hasMessage("Injected failure");

    // After increasing the number of retries the commit succeeds
    table
        .updateProperties()
        .set(
            TableProperties.COMMIT_NUM_RETRIES,
            String.valueOf(TableProperties.COMMIT_NUM_RETRIES_DEFAULT + 1))
        .commit();

    append.commit();

    validateSnapshot(null, readMetadata().currentSnapshot(), FILE_B);
  }

  @TestTemplate
  public void testAppendManifestCleanup() throws IOException {
    // inject 5 failures
    TestTables.TestTableOperations ops = table.ops();
    ops.failCommits(5);

    ManifestFile manifest = writeManifest(FILE_A, FILE_B);
    AppendFiles append = table.newFastAppend().appendManifest(manifest);
    Snapshot pending = append.apply();
    ManifestFile newManifest = pending.allManifests(FILE_IO).get(0);
    assertThat(new File(newManifest.path())).exists();
    if (formatVersion == 1) {
      assertThat(newManifest.path()).isNotEqualTo(manifest.path());
    } else {
      assertThat(newManifest.path()).isEqualTo(manifest.path());
    }

    assertThatThrownBy(append::commit)
        .isInstanceOf(CommitFailedException.class)
        .hasMessage("Injected failure");

    if (formatVersion == 1) {
      assertThat(new File(newManifest.path())).doesNotExist();
    } else {
      assertThat(new File(newManifest.path())).exists();
    }
  }

  @TestTemplate
  public void testRecoveryWithManifestList() {
    table.updateProperties().set(TableProperties.MANIFEST_LISTS_ENABLED, "true").commit();

    // inject 3 failures, the last try will succeed
    TestTables.TestTableOperations ops = table.ops();
    ops.failCommits(3);

    AppendFiles append = table.newFastAppend().appendFile(FILE_B);
    Snapshot pending = append.apply();
    ManifestFile newManifest = pending.allManifests(FILE_IO).get(0);
    assertThat(new File(newManifest.path())).exists();

    append.commit();

    TableMetadata metadata = readMetadata();

    validateSnapshot(null, metadata.currentSnapshot(), FILE_B);
    assertThat(new File(newManifest.path())).exists();
    assertThat(metadata.currentSnapshot().allManifests(FILE_IO)).contains(newManifest);
  }

  @TestTemplate
  public void testRecoveryWithoutManifestList() {
    table.updateProperties().set(TableProperties.MANIFEST_LISTS_ENABLED, "false").commit();

    // inject 3 failures, the last try will succeed
    TestTables.TestTableOperations ops = table.ops();
    ops.failCommits(3);

    AppendFiles append = table.newFastAppend().appendFile(FILE_B);
    Snapshot pending = append.apply();
    ManifestFile newManifest = pending.allManifests(FILE_IO).get(0);
    assertThat(new File(newManifest.path())).exists();

    append.commit();

    TableMetadata metadata = readMetadata();

    validateSnapshot(null, metadata.currentSnapshot(), FILE_B);
    assertThat(new File(newManifest.path())).exists();
    assertThat(metadata.currentSnapshot().allManifests(FILE_IO)).contains(newManifest);
  }

  @TestTemplate
  public void testWriteNewManifestsIdempotency() {
    // inject 3 failures, the last try will succeed
    TestTables.TestTableOperations ops = table.ops();
    ops.failCommits(3);

    AppendFiles append = table.newFastAppend().appendFile(FILE_B);
    Snapshot pending = append.apply();
    ManifestFile newManifest = pending.allManifests(FILE_IO).get(0);
    assertThat(new File(newManifest.path())).exists();

    append.commit();

    TableMetadata metadata = readMetadata();

    // contains only a single manifest, does not duplicate manifests on retries
    validateSnapshot(null, metadata.currentSnapshot(), FILE_B);
    assertThat(new File(newManifest.path())).exists();
    assertThat(metadata.currentSnapshot().allManifests(FILE_IO)).contains(newManifest);
    assertThat(listManifestFiles(tableDir)).containsExactly(new File(newManifest.path()));
  }

  @TestTemplate
  public void testWriteNewManifestsCleanup() {
    // append file, stage changes with apply() but do not commit
    AppendFiles append = table.newFastAppend().appendFile(FILE_A);
    Snapshot pending = append.apply();
    ManifestFile oldManifest = pending.allManifests(FILE_IO).get(0);
    assertThat(new File(oldManifest.path())).exists();

    // append file, stage changes with apply() but do not commit
    // validate writeNewManifests deleted the old staged manifest
    append.appendFile(FILE_B);
    Snapshot newPending = append.apply();
    List<ManifestFile> manifestFiles = newPending.allManifests(FILE_IO);
    assertThat(manifestFiles).hasSize(1);
    ManifestFile newManifest = manifestFiles.get(0);
    assertThat(newManifest.path()).isNotEqualTo(oldManifest.path());

    append.commit();
    TableMetadata metadata = readMetadata();

    // contains only a single manifest, old staged manifest is deleted
    validateSnapshot(null, metadata.currentSnapshot(), FILE_A, FILE_B);
    assertThat(new File(oldManifest.path())).doesNotExist();
    assertThat(new File(newManifest.path())).exists();
    assertThat(metadata.currentSnapshot().allManifests(FILE_IO)).containsExactly(newManifest);
    assertThat(listManifestFiles(tableDir)).containsExactly(new File(newManifest.path()));
  }

  @TestTemplate
  public void testAppendManifestWithSnapshotIdInheritance() throws IOException {
    table.updateProperties().set(TableProperties.SNAPSHOT_ID_INHERITANCE_ENABLED, "true").commit();

    assertThat(listManifestFiles()).isEmpty();

    TableMetadata base = readMetadata();
    assertThat(base.currentSnapshot()).isNull();

    ManifestFile manifest = writeManifest(FILE_A, FILE_B);
    table.newFastAppend().appendManifest(manifest).commit();

    Snapshot snapshot = table.currentSnapshot();
    List<ManifestFile> manifests = table.currentSnapshot().allManifests(FILE_IO);
    ManifestFile committedManifest = Iterables.getOnlyElement(manifests);
    assertThat(committedManifest.path()).isEqualTo(manifest.path());

    validateManifestEntries(
        manifests.get(0),
        ids(snapshot.snapshotId(), snapshot.snapshotId()),
        files(FILE_A, FILE_B),
        statuses(Status.ADDED, Status.ADDED));

    // validate that the metadata summary is correct when using appendManifest
    assertThat(snapshot.summary())
        .containsEntry("added-data-files", "2")
        .containsEntry("added-records", "2")
        .containsEntry("total-data-files", "2")
        .containsEntry("total-records", "2");
  }

  @TestTemplate
  public void testAppendManifestFailureWithSnapshotIdInheritance() throws IOException {
    table.updateProperties().set(TableProperties.SNAPSHOT_ID_INHERITANCE_ENABLED, "true").commit();

    assertThat(listManifestFiles()).isEmpty();

    TableMetadata base = readMetadata();
    assertThat(base.currentSnapshot()).isNull();

    table.updateProperties().set(TableProperties.COMMIT_NUM_RETRIES, "1").commit();

    table.ops().failCommits(5);

    ManifestFile manifest = writeManifest(FILE_A, FILE_B);

    AppendFiles append = table.newAppend();
    append.appendManifest(manifest);

    assertThatThrownBy(append::commit)
        .isInstanceOf(CommitFailedException.class)
        .hasMessage("Injected failure");

    assertThat(new File(manifest.path())).exists();
  }

  @TestTemplate
  public void testInvalidAppendManifest() throws IOException {
    assertThat(listManifestFiles()).isEmpty();

    TableMetadata base = readMetadata();
    assertThat(base.currentSnapshot()).isNull();

    ManifestFile manifestWithExistingFiles =
        writeManifest("manifest-file-1.avro", manifestEntry(Status.EXISTING, null, FILE_A));
    assertThatThrownBy(
            () -> table.newFastAppend().appendManifest(manifestWithExistingFiles).commit())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Cannot append manifest with existing files");

    ManifestFile manifestWithDeletedFiles =
        writeManifest("manifest-file-2.avro", manifestEntry(Status.DELETED, null, FILE_A));
    assertThatThrownBy(
            () -> table.newFastAppend().appendManifest(manifestWithDeletedFiles).commit())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Cannot append manifest with deleted files");
  }

  @TestTemplate
  public void testPartitionSummariesOnUnpartitionedTable() {
    Table table =
        TestTables.create(
            tableDir,
            "x",
            SCHEMA,
            PartitionSpec.unpartitioned(),
            SortOrder.unsorted(),
            formatVersion);

    table.updateProperties().set(TableProperties.WRITE_PARTITION_SUMMARY_LIMIT, "1").commit();
    table
        .newFastAppend()
        .appendFile(
            DataFiles.builder(PartitionSpec.unpartitioned())
                .withPath("/path/to/data-a.parquet")
                .withFileSizeInBytes(10)
                .withRecordCount(1)
                .build())
        .commit();

    assertThat(
            table.currentSnapshot().summary().keySet().stream()
                .filter(key -> key.startsWith(SnapshotSummary.CHANGED_PARTITION_PREFIX))
                .collect(Collectors.toSet()))
        .as("Should not include any partition summaries")
        .isEmpty();
  }

  @TestTemplate
  public void testDefaultPartitionSummaries() {
    table.newFastAppend().appendFile(FILE_A).commit();

    Set<String> partitionSummaryKeys =
        table.currentSnapshot().summary().keySet().stream()
            .filter(key -> key.startsWith(SnapshotSummary.CHANGED_PARTITION_PREFIX))
            .collect(Collectors.toSet());
    assertThat(partitionSummaryKeys).isEmpty();

    assertThat(table.currentSnapshot().summary())
        .doesNotContainKey(SnapshotSummary.PARTITION_SUMMARY_PROP)
        .containsEntry(SnapshotSummary.CHANGED_PARTITION_COUNT_PROP, "1");
  }

  @TestTemplate
  public void testIncludedPartitionSummaries() {
    table.updateProperties().set(TableProperties.WRITE_PARTITION_SUMMARY_LIMIT, "1").commit();

    table.newFastAppend().appendFile(FILE_A).commit();

    Set<String> partitionSummaryKeys =
        table.currentSnapshot().summary().keySet().stream()
            .filter(key -> key.startsWith(SnapshotSummary.CHANGED_PARTITION_PREFIX))
            .collect(Collectors.toSet());
    assertThat(partitionSummaryKeys).hasSize(1);

    assertThat(table.currentSnapshot().summary())
        .containsEntry(SnapshotSummary.PARTITION_SUMMARY_PROP, "true")
        .containsEntry(SnapshotSummary.CHANGED_PARTITION_COUNT_PROP, "1")
        .containsEntry(
            SnapshotSummary.CHANGED_PARTITION_PREFIX + "data_bucket=0",
            "added-data-files=1,added-records=1,added-files-size=10");
  }

  @TestTemplate
  public void testIncludedPartitionSummaryLimit() {
    table.updateProperties().set(TableProperties.WRITE_PARTITION_SUMMARY_LIMIT, "1").commit();

    table.newFastAppend().appendFile(FILE_A).appendFile(FILE_B).commit();

    Set<String> partitionSummaryKeys =
        table.currentSnapshot().summary().keySet().stream()
            .filter(key -> key.startsWith(SnapshotSummary.CHANGED_PARTITION_PREFIX))
            .collect(Collectors.toSet());
    assertThat(partitionSummaryKeys).isEmpty();

    assertThat(table.currentSnapshot().summary())
        .doesNotContainKey(SnapshotSummary.PARTITION_SUMMARY_PROP)
        .containsEntry(SnapshotSummary.CHANGED_PARTITION_COUNT_PROP, "2");
  }

  @TestTemplate
  public void testAppendToExistingBranch() {
    table.newFastAppend().appendFile(FILE_A).commit();
    table.manageSnapshots().createBranch("branch", table.currentSnapshot().snapshotId()).commit();
    table.newFastAppend().appendFile(FILE_B).toBranch("branch").commit();

    assertThat(table.currentSnapshot().snapshotId()).isEqualTo(1);
    assertThat(table.ops().current().ref("branch").snapshotId()).isEqualTo(2);
  }

  @TestTemplate
  public void testAppendCreatesBranchIfNeeded() {
    table.newFastAppend().appendFile(FILE_A).commit();
    table.newFastAppend().appendFile(FILE_B).toBranch("branch").commit();

    assertThat(table.currentSnapshot().snapshotId()).isEqualTo(1);
    assertThat(table.ops().current().ref("branch")).isNotNull();
    assertThat(table.ops().current().ref("branch").snapshotId()).isEqualTo(2);
  }

  @TestTemplate
  public void testAppendToBranchEmptyTable() {
    table.newFastAppend().appendFile(FILE_B).toBranch("branch").commit();

    assertThat(table.currentSnapshot()).isNull();
    assertThat(table.ops().current().ref("branch")).isNotNull();
    assertThat(table.ops().current().ref("branch").snapshotId()).isEqualTo(1);
  }

  @TestTemplate
  public void testAppendToNullBranchFails() {
    assertThatThrownBy(() -> table.newFastAppend().appendFile(FILE_A).toBranch(null))
        .as("Invalid branch")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid branch name: null");
  }

  @TestTemplate
  public void testAppendToTagFails() {
    table.newFastAppend().appendFile(FILE_A).commit();
    table.manageSnapshots().createTag("some-tag", table.currentSnapshot().snapshotId()).commit();

    assertThatThrownBy(() -> table.newFastAppend().appendFile(FILE_A).toBranch("some-tag").commit())
        .as("Invalid branch")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "some-tag is a tag, not a branch. Tags cannot be targets for producing snapshots");
  }
}
