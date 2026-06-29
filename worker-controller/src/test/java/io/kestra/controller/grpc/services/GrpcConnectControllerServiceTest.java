package io.kestra.controller.grpc.services;

import org.junit.jupiter.api.Test;

import io.kestra.controller.grpc.ConnectRequest;
import io.kestra.core.worker.WorkerGroups;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GrpcConnectControllerServiceTest {

    @Test
    void shouldUseRequestedWorkerGroup() {
        GrpcConnectControllerService service = new GrpcConnectControllerService(mock(WorkerConfigsProvider.class));

        String result = service.resolveWorkerGroupId(ConnectRequest.newBuilder()
            .setRequestedWorkerGroupId("gce-a")
            .build());

        assertThat(result).isEqualTo("gce-a");
    }

    @Test
    void shouldDefaultBlankRequestedWorkerGroup() {
        GrpcConnectControllerService service = new GrpcConnectControllerService(mock(WorkerConfigsProvider.class));

        String result = service.resolveWorkerGroupId(ConnectRequest.newBuilder().build());

        assertThat(result).isEqualTo(WorkerGroups.DEFAULT_ID);
    }
}
