package com.emc.logservice.server.containers;

import com.emc.logservice.common.FutureHelpers;
import com.emc.logservice.common.TimeoutTimer;
import com.emc.logservice.contracts.SegmentProperties;
import com.emc.logservice.contracts.StreamSegmentNotExistsException;
import com.emc.logservice.server.*;
import com.emc.logservice.server.logs.OperationLog;
import com.emc.logservice.server.logs.operations.BatchMapOperation;
import com.emc.logservice.server.logs.operations.StreamSegmentMapOperation;
import com.emc.logservice.storageabstraction.Storage;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Helps assign unique Ids to StreamSegments and persists them in Metadata.
 */
public class StreamSegmentMapper {
    //region Members

    private final UpdateableContainerMetadata containerMetadata;
    private final OperationLog durableLog;
    private final Storage storage;
    private final HashMap<String, CompletableFuture<Long>> pendingRequests;
    private final HashSet<Long> pendingIdAssignments;
    private final Object SyncRoot = new Object();
    private final Object MetadataLock = new Object();

    //endregion

    //region Constructor

    /**
     * Creates a new instance of the StreamSegmentMapper class.
     *
     * @param containerMetadata The StreamSegmentContainerMetadata to bind to. All assignments are vetted and stored here,
     *                          but the Metadata is not persisted with every assignment.
     * @param durableLog        The Durable Log to bind to. All assignments are durably stored here (the metadata is not persisted
     *                          with every stream map)
     * @throws NullPointerException If any of the arguments are null.
     */
    public StreamSegmentMapper(UpdateableContainerMetadata containerMetadata, OperationLog durableLog, Storage storage) {
        if (containerMetadata == null) {
            throw new NullPointerException("containerMetadata");
        }

        if (durableLog == null) {
            throw new NullPointerException("durableLog");
        }
        if (storage == null) {
            throw new NullPointerException("storage");
        }

        this.containerMetadata = containerMetadata;
        this.durableLog = durableLog;
        this.storage = storage;
        this.pendingRequests = new HashMap<>();
        this.pendingIdAssignments = new HashSet<>();
    }

    //endregion

    //region Operations

    /**
     * Creates a new StreamSegment with given name (in Storage) and assigns a unique internal Id to it.
     *
     * @param streamSegmentName The case-sensitive StreamSegment Name.
     * @param timeout           Timeout for the operation.
     * @return A CompletableFuture that, when completed normally, will indicate the operation completed normally.
     * If the operation failed, this will contain the exception that caused the failure.
     */
    public CompletableFuture<Void> createNewStreamSegment(String streamSegmentName, Duration timeout) {
        long streamId = this.containerMetadata.getStreamSegmentId(streamSegmentName);
        if (isValidStreamSegmentId(streamId)) {
            throw new IllegalArgumentException("Given StreamSegmentName is already registered internally. Most likely it already exists.");
        }

        // Create the StreamSegment, and then assign a Unique Internal Id to it.
        // Note: this is slightly sub-optimal, as we create the stream, but getOrAssignStreamSegmentId makes another call
        // to get the same info about the StreamSegmentId.
        TimeoutTimer timer = new TimeoutTimer(timeout);
        CompletableFuture<Long> result = this.storage.create(streamSegmentName, timer.getRemaining())
                                                     .thenCompose(si -> getOrAssignStreamSegmentId(si.getName(), timer.getRemaining()));
        return CompletableFuture.allOf(result);
    }

    /**
     * Creates a new Batch StreamSegment for an existing Parent StreamSegment and assigns a unique internal Id to it.
     *
     * @param parentStreamSegmentName The case-sensitive StreamSegment Name of the Parent StreamSegment.
     * @param timeout                 Timeout for the operation.
     * @return A CompletableFuture that, when completed normally, will contain the name of the newly created Batch StreamSegment.
     * If the operation failed, this will contain the exception that caused the failure.
     * @throws IllegalArgumentException If the given parent StreamSegment is invalid to have a batch (deleted, sealed, inexistent).
     */
    public CompletableFuture<String> createNewBatchStreamSegment(String parentStreamSegmentName, Duration timeout) {
        if (StreamSegmentNameUtils.getParentStreamSegmentName(parentStreamSegmentName) != null) {
            //We cannot create a Batch StreamSegment for a what looks like a parent StreamSegment.
            throw new IllegalArgumentException("Given Parent StreamSegmentName looks like a Batch StreamSegment Name. Cannot create a batch for a batch.");
        }

        // Validate that Parent StreamSegment exists.
        CompletableFuture<SegmentProperties> parentPropertiesFuture = null;
        long parentStreamSegmentId = this.containerMetadata.getStreamSegmentId(parentStreamSegmentName);
        if (isValidStreamSegmentId(parentStreamSegmentId)) {
            SegmentMetadata parentMetadata = this.containerMetadata.getStreamSegmentMetadata(parentStreamSegmentId);
            if (parentMetadata != null) {
                if (isValidStreamSegmentId(parentMetadata.getParentId())) {
                    throw new IllegalArgumentException("Given Parent StreamSegment is a Batch StreamSegment. Cannot create a batch for a batch.");
                }

                if (parentMetadata.isDeleted() || parentMetadata.isSealed()) {
                    throw new IllegalArgumentException("Given Parent StreamSegment is deleted or sealed. Cannot create a batch for it.");
                }

                parentPropertiesFuture = CompletableFuture.completedFuture(parentMetadata);
            }
        }

        //TODO: verify the batch name doesn't already exist. It is possible that two concurrent calls to createBatch can create the same batch name.
        String batchName = StreamSegmentNameUtils.generateBatchStreamSegmentName(parentStreamSegmentName);

        TimeoutTimer timer = new TimeoutTimer(timeout);
        if (parentPropertiesFuture == null) {
            // We were unable to find this StreamSegment in our metadata. Check in Storage. If the parent StreamSegment
            // does not exist, this will throw an exception (and place it on the resulting future).
            parentPropertiesFuture = this.storage.getStreamSegmentInfo(parentStreamSegmentName, timer.getRemaining());
        }

        return parentPropertiesFuture
                .thenCompose(parentInfo -> this.storage.create(batchName, timer.getRemaining()))
                .thenCompose(batchInfo -> assignBatchStreamSegmentId(batchInfo, parentStreamSegmentId, timer.getRemaining()))
                .thenApply(id -> batchName);
    }

    /**
     * Attempts to get an existing StreamSegmentId for the given case-sensitive StreamSegment Name.
     * If no such mapping exists, atomically assigns a new one and stores it in the Metadata and DurableLog.
     * <p>
     * If multiple requests for assignment arrive for the same StreamSegment in parallel, the subsequent ones (after the
     * first one) will wait for the first one to complete and return the same result (this will not result in double-assignment).
     * TODO: figure out if streamSegmentName is a batch or not. We want to create the proper mappings in the metadata.
     * @param streamSegmentName The case-sensitive StreamSegment Name.
     * @param timeout           The timeout for the operation.
     * @return A CompletableFuture that, when completed normally, will contain the StreamSegment Id requested. If the operation
     * failed, this will contain the exception that caused the failure.
     */
    public CompletableFuture<Long> getOrAssignStreamSegmentId(String streamSegmentName, Duration timeout) {
        // Check to see if the metadata already knows about this stream.
        long streamId = this.containerMetadata.getStreamSegmentId(streamSegmentName);
        if (isValidStreamSegmentId(streamId)) {
            // We already have a value, just return it.
            return CompletableFuture.completedFuture(streamId);
        }

        // See if anyone else is currently waiting to get this StreamSegment's id.
        CompletableFuture<Long> result;
        boolean needsAssignment = false;
        synchronized (SyncRoot) {
            result = this.pendingRequests.getOrDefault(streamSegmentName, null);
            if (result == null) {
                needsAssignment = true;
                result = new CompletableFuture<>();
                this.pendingRequests.put(streamSegmentName, result);
            }
        }

        // We are the first/only ones requesting this id; go ahead and assign an id.
        if (needsAssignment) {
            //TODO: use a better thread pool.
            CompletableFuture.runAsync(() -> assignStreamId(streamSegmentName, timeout));
        }

        return result;
    }

    /**
     * Attempts to get an existing StreamSegmentId for the given case-sensitive StreamSegment Name.
     * If no such mapping exists, atomically assigns a new one and stores it in the Metadata and DurableLog.
     * <p>
     * If multiple requests for assignment arrive for the same StreamSegment in parallel, the subsequent ones (after the
     * first one) will wait for the first one to complete and return the same result (this will not result in double-assignment).
     *
     * @param batchInfo             The SegmentProperties for the newly created batch.
     * @param parentStreamSegmentId The ID of the Parent StreamSegment.
     * @param timeout               The timeout for the operation.
     * @return A CompletableFuture that, when completed normally, will contain the StreamSegment Id requested. If the operation
     * failed, this will contain the exception that caused the failure.
     */
    private CompletableFuture<Long> assignBatchStreamSegmentId(SegmentProperties batchInfo, long parentStreamSegmentId, Duration timeout) {
        assert batchInfo != null;
        assert parentStreamSegmentId != SegmentMetadataCollection.NoStreamSegmentId;
        TimeoutTimer timer = new TimeoutTimer(timeout);
        return persistInDurableLog(batchInfo, parentStreamSegmentId, timer.getRemaining());
    }

    /**
     * Assigns a new Id to the given StreamSegmentName.
     *
     * @param streamSegmentName
     * @param timeout
     */
    private void assignStreamId(String streamSegmentName, Duration timeout) {
        TimeoutTimer timer = new TimeoutTimer(timeout);
        this.storage.getStreamSegmentInfo(streamSegmentName, timer.getRemaining())
                    .thenCompose(streamInfo -> persistInDurableLog(streamInfo, timer.getRemaining()))
                    .exceptionally(ex ->
                    {
                        failAssignment(streamSegmentName, SegmentMetadataCollection.NoStreamSegmentId, ex);
                        throw new CompletionException(ex);
                    });
    }

    /**
     * Generates a unique Id for the StreamSegment with given info and persists that in DurableLog.
     *
     * @param streamSegmentInfo The SegmentProperties for the StreamSegment to generate and persist.
     * @param timeout
     * @return
     */
    private CompletableFuture<Long> persistInDurableLog(SegmentProperties streamSegmentInfo, Duration timeout) {
        return persistInDurableLog(streamSegmentInfo, SegmentMetadataCollection.NoStreamSegmentId, timeout);
    }

    /**
     * Generates a unique Id for the StreamSegment with given info and persists that in DurableLog.
     *
     * @param streamSegmentInfo     The SegmentProperties for the StreamSegment to generate and persist.
     * @param parentStreamSegmentId If different from SegmentMetadataCollection.NoStreamSegmentId, the given streamSegmentInfo
     *                              will be mapped as a batch. Otherwise, this will be registered as a standalone StreamSegment.
     * @param timeout
     * @return
     */
    private CompletableFuture<Long> persistInDurableLog(SegmentProperties streamSegmentInfo, long parentStreamSegmentId, Duration timeout) {
        if (streamSegmentInfo.isDeleted()) {
            // Stream does not exist. Fail the request with the appropriate exception.
            failAssignment(streamSegmentInfo.getName(), SegmentMetadataCollection.NoStreamSegmentId, new StreamSegmentNotExistsException("StreamSegment does not exist."));
            return FutureHelpers.failedFuture(new StreamSegmentNotExistsException(streamSegmentInfo.getName()));
        }

        long streamId = this.containerMetadata.getStreamSegmentId(streamSegmentInfo.getName());
        if (isValidStreamSegmentId(streamId)) {
            // Looks like someone else beat us to it.
            completeAssignment(streamSegmentInfo.getName(), streamId);
            return CompletableFuture.completedFuture(streamId);
        }
        else {
            final long newStreamId = generateUniqueStreamId(streamSegmentInfo.getName());
            CompletableFuture<Long> logAddResult;
            if (isValidStreamSegmentId(parentStreamSegmentId)) {
                // Batch.
                SegmentMetadata parentMetadata = this.containerMetadata.getStreamSegmentMetadata(parentStreamSegmentId);
                assert parentMetadata != null;
                logAddResult = this.durableLog.add(new BatchMapOperation(parentStreamSegmentId, newStreamId, streamSegmentInfo), timeout);
            }
            else {
                // Standalone StreamSegment.
                logAddResult = this.durableLog.add(new StreamSegmentMapOperation(newStreamId, streamSegmentInfo), timeout);
            }
            return logAddResult.thenApply(seqNo ->
            {
                updateMetadata(newStreamId, streamSegmentInfo, parentStreamSegmentId);
                completeAssignment(streamSegmentInfo.getName(), newStreamId);
                return newStreamId;
            });
        }
    }

    /**
     * Updates metadata with the new mapping.
     *
     * @param streamSegmentId       The Id of the new StreamSegment to map.
     * @param streamSegmentInfo     The SegmentProperties for the new StreamSegment.
     * @param parentStreamSegmentId If equal to SegmentMetadataCollection.NoStreamSegmentId, this will be mapped as a
     *                              standalone StreamSegment. Otherwise, it will be mapped as a batch to the given
     *                              parentStreamSegmentId.
     */
    private void updateMetadata(long streamSegmentId, SegmentProperties streamSegmentInfo, long parentStreamSegmentId) {
        synchronized (MetadataLock) {
            // Map it to the stream name and update the Stream Metadata.
            if(isValidStreamSegmentId(parentStreamSegmentId)){
                // Batch StreamSegment.
                this.containerMetadata.mapStreamSegmentId(streamSegmentInfo.getName(), streamSegmentId, parentStreamSegmentId);
            }
            else {
                // Standalone StreamSegment.
                this.containerMetadata.mapStreamSegmentId(streamSegmentInfo.getName(), streamSegmentId);
            }

            UpdateableSegmentMetadata sm = this.containerMetadata.getStreamSegmentMetadata(streamSegmentId);
            sm.setStorageLength(streamSegmentInfo.getLength());
            sm.setDurableLogLength(streamSegmentInfo.getLength()); // TODO: this will need to be set/reset in recovery. This is the default (failback) value.

            if (streamSegmentInfo.isSealed()) {
                sm.markSealed();
            }

            // No need to 'markDeleted()' because that would have triggered an exception upstream and we
            // wouldn't have gotten here in the first place.
        }
    }

    /**
     * Generates a unique StreamSegment Id that does not currently exist in the Metadata or in the pending Id assingments.
     *
     * @param streamSegmentName
     * @return
     */
    private long generateUniqueStreamId(String streamSegmentName) {
        // Get the last 32 bits of the current time (in millis), and move those to the upper portion of our ID.
        long streamId = System.currentTimeMillis() << 32;
        streamId |= streamSegmentName.hashCode() & 0xffffffffL;
        synchronized (SyncRoot) {
            while (!isValidStreamSegmentId(streamId) || this.containerMetadata.getStreamSegmentMetadata(streamId) != null || this.pendingIdAssignments.contains(streamId)) {
                streamId++;
            }

            this.pendingIdAssignments.add(streamId);
        }

        return streamId;
    }

    /**
     * Completes the assignment for the given StreamSegmentName by completing the waiting CompletableFuture.
     *
     * @param streamSegmentName
     * @param streamSegmentId
     */
    private void completeAssignment(String streamSegmentName, long streamSegmentId) {
        // Get the pending request and complete it.
        CompletableFuture<Long> pendingRequest;
        synchronized (SyncRoot) {
            pendingRequest = this.pendingRequests.getOrDefault(streamSegmentName, null);
            this.pendingRequests.remove(streamSegmentName);
            this.pendingIdAssignments.remove(streamSegmentId);
        }

        if (pendingRequest != null) {
            pendingRequest.complete(streamSegmentId);
        }
    }

    /**
     * Fails the assignment for the given StreamSegment Id with the given reason.
     *
     * @param streamSegmentName Required.
     * @param streamSegmentId   Optional
     * @param reason
     */
    private void failAssignment(String streamSegmentName, long streamSegmentId, Throwable reason) {
        // Get the pending request and complete it.
        CompletableFuture<Long> pendingRequest;
        synchronized (SyncRoot) {
            pendingRequest = this.pendingRequests.getOrDefault(streamSegmentName, null);
            this.pendingRequests.remove(streamSegmentName);
            this.pendingIdAssignments.remove(streamSegmentId);
        }

        if (pendingRequest != null) {
            pendingRequest.completeExceptionally(reason);
        }
    }

    private boolean isValidStreamSegmentId(long id) {
        return id != SegmentMetadataCollection.NoStreamSegmentId;
    }

    //endregion
}
