package br.com.paralela.tp1;

import br.com.paralela.tp1.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class PrintingClient {

  private final int id;
  private final int port;
  private final String serverAddress;
  private Server gRpcServer;

  private final PrintingServiceGrpc.PrintingServiceBlockingStub printerStub;
  private final Map<Integer, MutualExclusionServiceGrpc.MutualExclusionServiceBlockingStub> peerStubs = new ConcurrentHashMap<>();

  public enum State {
    RELEASED, WANTED, HELD
  }

  private volatile State state = State.RELEASED;

  private final AtomicLong lamportClock = new AtomicLong(0);
  private final AtomicInteger requestCounter = new AtomicInteger(0);

  private volatile long ourRequestTimestamp = -1;
  private volatile int ourRequestNumber = -1;

  private final Set<Integer> deferredReplies = ConcurrentHashMap.newKeySet();

  private final Object lock = new Object();
  private final ExecutorService executor = Executors.newCachedThreadPool();

  public PrintingClient(int id, int port, String serverAddress, Map<Integer, String> peerAddresses) {
    this.id = id;
    this.port = port;
    this.serverAddress = serverAddress;

    ManagedChannel printerChannel = ManagedChannelBuilder.forTarget(serverAddress).usePlaintext().build();
    this.printerStub = PrintingServiceGrpc.newBlockingStub(printerChannel);

    for (Map.Entry<Integer, String> peer : peerAddresses.entrySet()) {
      ManagedChannel peerChannel = ManagedChannelBuilder.forTarget(peer.getValue()).usePlaintext().build();
      this.peerStubs.put(peer.getKey(), MutualExclusionServiceGrpc.newBlockingStub(peerChannel));
    }

    System.out.printf("[Cliente %d] Conectado ao Servidor %s e a %d pares.\n", id, serverAddress, peerStubs.size());
  }

  public long tick() {
    return lamportClock.incrementAndGet();
  }

  private void updateClock(long receivedTimestamp) {
    lamportClock.updateAndGet(current -> Math.max(current, receivedTimestamp) + 1);
  }

  private long getClock() {
    return lamportClock.get();
  }

  public void start() throws IOException {
    gRpcServer = ServerBuilder.forPort(port)
        .addService(new MutualExclusionServiceImpl(this))
        .build();
    gRpcServer.start();
    System.out.printf("[Cliente %d] Servidor de Exclusão Mútua iniciado na porta %d.\n", id, port);

    executor.submit(this::simulationLoop);
  }

  public void stop() {
    executor.shutdownNow();
    gRpcServer.shutdownNow();
  }

  private void simulationLoop() {
    Random random = new Random();
    try {
      Thread.sleep(5000 + random.nextInt(2000));

      while (!Thread.currentThread().isInterrupted()) {
        Thread.sleep(5000 + random.nextInt(7000));
        requestCriticalSection();
      }
    } catch (InterruptedException e) {
      System.out.printf("[Cliente %d] Loop de simulação interrompido.\n", id);
      Thread.currentThread().interrupt();
    }
  }

  private void requestCriticalSection() {
    synchronized (lock) {
      state = State.WANTED;
      ourRequestTimestamp = tick();
      ourRequestNumber = requestCounter.incrementAndGet();

      System.out.printf("[Cliente %d] MODO: WANTED (TS: %d, Req: %d)\n", id, ourRequestTimestamp, ourRequestNumber);
    }

    AccessRequest request = AccessRequest.newBuilder()
        .setClientId(id)
        .setLamportTimestamp(ourRequestTimestamp)
        .setRequestNumber(ourRequestNumber)
        .build();

    CountDownLatch latch = new CountDownLatch(peerStubs.size());

    for (Map.Entry<Integer, MutualExclusionServiceGrpc.MutualExclusionServiceBlockingStub> entry : peerStubs
        .entrySet()) {
      int peerId = entry.getKey();
      MutualExclusionServiceGrpc.MutualExclusionServiceBlockingStub stub = entry.getValue();

      executor.submit(() -> {
        try {
          System.out.printf("[Cliente %d] Enviando REQ para Cliente %d (TS: %d, Req: %d)\n", id, peerId,
              ourRequestTimestamp, ourRequestNumber);
          AccessResponse response = stub.requestAccess(request);
          updateClock(response.getLamportTimestamp());
          System.out.printf("[Cliente %d] Recebido GRANT do Cliente %d (Seu TS: %d, Meu TS agora: %d)\n", id, peerId,
              response.getLamportTimestamp(), getClock());

        } catch (Exception e) {
          System.err.printf("[Cliente %d] Falha ao contatar Cliente %d: %s\n", id, peerId, e.getMessage());
        } finally {
          latch.countDown();
        }
      });
    }

    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      System.err.printf("[Cliente %d] Fui interrompido enquanto esperava por grants.\n", id);
      return;
    }

    accessCriticalSection();
  }

  private void accessCriticalSection() {
    synchronized (lock) {
      state = State.HELD;
      System.out.printf("[Cliente %d] MODO: HELD (Entrando na Seção Crítica...)\n", id);
    }

    try {
      String message = "Trabalho de SD - Cliente " + id + " imprimindo.";
      long printRequestTimestamp = tick();

      PrintRequest request = PrintRequest.newBuilder()
          .setClientId(id)
          .setMessageContent(message)
          .setLamportTimestamp(printRequestTimestamp)
          .setRequestNumber(ourRequestNumber)
          .build();

      System.out.printf("[Cliente %d] Enviando para Impressora (TS: %d, Req: %d)...\n", id, printRequestTimestamp,
          ourRequestNumber);
      PrintResponse response = printerStub.sendToPrinter(request);
      System.out.printf("[Cliente %d] Impressora respondeu: %s\n", id, response.getConfirmationMessage());

    } catch (Exception e) {
      System.err.printf("[Cliente %d] Erro ao acessar a impressora: %s\n", id, e.getMessage());
    } finally {
      releaseCriticalSection();
    }
  }

  private void releaseCriticalSection() {
    Set<Integer> deferredToNotify;
    synchronized (lock) {
      state = State.RELEASED;
      ourRequestTimestamp = -1;
      ourRequestNumber = -1;

      System.out.printf("[Cliente %d] MODO: RELEASED (Saindo da Seção Crítica)\n", id);

      deferredToNotify = new HashSet<>(deferredReplies);
      deferredReplies.clear();

      lock.notifyAll();
    }
  }

  public static void main(String[] args) throws IOException, InterruptedException {
    int id = -1;
    int port = -1;
    String serverAddress = null;
    Map<Integer, String> peerAddresses = new HashMap<>();

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--id":
          id = Integer.parseInt(args[++i]);
          break;
        case "--port":
          port = Integer.parseInt(args[++i]);
          break;
        case "--server":
          serverAddress = args[++i];
          break;
        case "--peer":
          String[] parts = args[++i].split(":");
          peerAddresses.put(Integer.parseInt(parts[0]), parts[1] + ":" + parts[2]);
          break;
      }
    }

    if (id == -1 || port == -1 || serverAddress == null) {
      System.err.println(
          "Uso: java PrintingClient --id <ID> --port <PORTA> --server <IP:PORTA_SERVIDOR> [--peer <ID:IP:PORTA> ...]");
      System.exit(1);
    }

    final PrintingClient client = new PrintingClient(id, port, serverAddress, peerAddresses);

    Runtime.getRuntime().addShutdownHook(new Thread(client::stop));
    client.start();

    while (true) {
      Thread.sleep(1000);
    }
  }

  public int getId() {
    return id;
  }

  public State getState() {
    return state;
  }

  public long getOurRequestTimestamp() {
    return ourRequestTimestamp;
  }

  public int getOurRequestNumber() {
    return ourRequestNumber;
  }

  public Set<Integer> getDeferredReplies() {
    return deferredReplies;
  }

  public Object getLock() {
    return lock;
  }

  public void updateClientClock(long ts) {
    updateClock(ts);
  }

  public long getClientClock() {
    return getClock();
  }
}
