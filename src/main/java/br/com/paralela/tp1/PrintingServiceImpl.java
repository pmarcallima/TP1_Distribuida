package br.com.paralela.tp1;

import br.com.paralela.tp1.grpc.PrintRequest;
import br.com.paralela.tp1.grpc.PrintResponse;
import br.com.paralela.tp1.grpc.PrintingServiceGrpc;
import io.grpc.stub.StreamObserver;

import java.util.Random;

public class PrintingServiceImpl extends PrintingServiceGrpc.PrintingServiceImplBase {

  private final Random random = new Random();

  @Override
  public void sendToPrinter(PrintRequest request, StreamObserver<PrintResponse> responseObserver) {
    String logMessage = String.format("[TS: %d] CLIENTE %d: %s (Req: %d)",
        request.getLamportTimestamp(),
        request.getClientId(),
        request.getMessageContent(),
        request.getRequestNumber());

    System.out.println("IMPRIMINDO... " + logMessage);

    try {
      long delay = 2000 + random.nextInt(1001);
      Thread.sleep(delay);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      System.err.println("Impressão interrompida.");
    }

    String confirmMsg = "Mensagem impressa com sucesso para Cliente " + request.getClientId();
    PrintResponse response = PrintResponse.newBuilder()
        .setSuccess(true)
        .setConfirmationMessage(confirmMsg)
        .build();

    System.out.println("IMPRESSÃO CONCLUÍDA. Respondendo ao Cliente " + request.getClientId());

    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }
}
