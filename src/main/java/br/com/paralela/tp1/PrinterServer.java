package br.com.paralela.tp1;

import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;

public class PrinterServer {

  public static void main(String[] args) throws IOException, InterruptedException {
    int port = 50051;

    for (String arg : args) {
      if (arg.startsWith("--port=")) {
        port = Integer.parseInt(arg.split("=")[1]);
      }
    }

    Server server = ServerBuilder.forPort(port)
        .addService(new PrintingServiceImpl())
        .build();

    server.start();
    System.out.println("--- Servidor de Impressão 'Burro' iniciado na porta " + port + " ---");
    System.out.println("Aguardando requisições de impressão...");

    server.awaitTermination();
  }
}
