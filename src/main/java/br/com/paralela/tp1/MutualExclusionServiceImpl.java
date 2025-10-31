package br.com.paralela.tp1;

import br.com.paralela.tp1.grpc.AccessRequest;
import br.com.paralela.tp1.grpc.AccessResponse;
import br.com.paralela.tp1.grpc.AccessRelease;
import br.com.paralela.tp1.grpc.MutualExclusionServiceGrpc;
import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;

public class MutualExclusionServiceImpl extends MutualExclusionServiceGrpc.MutualExclusionServiceImplBase {

  private final PrintingClient client;

  public MutualExclusionServiceImpl(PrintingClient client) {
    this.client = client;
  }

  @Override
  public void requestAccess(AccessRequest request, StreamObserver<AccessResponse> responseObserver) {
    client.updateClientClock(request.getLamportTimestamp());

    System.out.printf("[Cliente %d] Recebido REQ do Cliente %d (Seu TS: %d, Meu TS agora: %d)\n",
        client.getId(), request.getClientId(), request.getLamportTimestamp(), client.getClientClock());

    synchronized (client.getLock()) {

      boolean weHavePriority = (client.getState() == PrintingClient.State.WANTED &&
          (client.getOurRequestTimestamp() < request.getLamportTimestamp() ||
              (client.getOurRequestTimestamp() == request.getLamportTimestamp() &&
                  client.getOurRequestNumber() < request.getRequestNumber())));

      boolean mustDefer = (client.getState() == PrintingClient.State.HELD) || weHavePriority;

      if (mustDefer) {
        System.out.printf("[Cliente %d] DEFERINDO resposta para Cliente %d\n", client.getId(), request.getClientId());
        client.getDeferredReplies().add(request.getClientId());

        try {
          while (client.getDeferredReplies().contains(request.getClientId())) {
            client.getLock().wait();
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          System.err.printf("[Cliente %d] Thread de handler interrompida para Cliente %d\n", client.getId(),
              request.getClientId());
        }

        System.out.printf("[Cliente %d] Fim da espera. Respondendo GRANT para Cliente %d\n", client.getId(),
            request.getClientId());

      } else {
        System.out.printf("[Cliente %d] Respondendo GRANT imediato para Cliente %d\n", client.getId(),
            request.getClientId());
      }

      AccessResponse response = AccessResponse.newBuilder()
          .setLamportTimestamp(client.tick())
          .build();

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  public void releaseAccess(AccessRelease request, StreamObserver<Empty> responseObserver) {
    client.updateClientClock(request.getLamportTimestamp());
    System.out.printf("[Cliente %d] Recebido 'ReleaseAccess' (NÃO UTILIZADO) do Cliente %d\n",
        client.getId(), request.getClientId());

    responseObserver.onNext(Empty.newBuilder().build());
    responseObserver.onCompleted();
  }
}
