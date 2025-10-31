# Sistema de Impressão Distribuída (Trabalho Prático 1)

Este projeto implementa um sistema de impressão distribuída que utiliza o algoritmo de **Ricart-Agrawala** para garantir exclusão mútua e **Relógios Lógicos de Lamport** para ordenação de eventos. A comunicação entre os processos é feita via **gRPC**.

O sistema simula um cenário onde múltiplos clientes ("inteligentes") disputam o acesso a um recurso compartilhado (um servidor de impressão "burro") que só pode atender um pedido por vez.

---

## 1. O Que Foi Implementado?

O projeto consiste em duas aplicações principais:

1.  **O Servidor "Burro" (`PrinterServer`):**
    * Uma aplicação gRPC simples que expõe um único serviço: `PrintingService`.
    * Ele não possui "inteligência", ou seja, **não participa** do algoritmo de exclusão mútua.
    * Sua única função é receber uma requisição de impressão, imprimir a mensagem no console, simular um "tempo de impressão" (com `Thread.sleep`) e retornar uma confirmação.

2.  **O Cliente "Inteligente" (`PrintingClient`):**
    * A aplicação principal que implementa toda a lógica do trabalho.
    * **Atua como Cliente gRPC:** Para enviar requisições ao Servidor "Burro" (`PrintingService`) e para enviar requisições de acesso aos seus pares (`MutualExclusionService`).
    * **Atua como Servidor gRPC:** Para *receber* requisições de acesso (`MutualExclusionService`) de outros clientes.
    * Implementa o algoritmo completo de **Ricart-Agrawala** para decidir quem pode acessar a "seção crítica" (ou seja, enviar a mensagem para o Servidor Burro).
    * Implementa os **Relógios Lógicos de Lamport** para ordenar todos os eventos (requisições, respostas) no sistema.

## 2. Por Que e Como Foi Implementado?

### 2.1. O Problema: A Seção Crítica

O "porquê" deste projeto é a necessidade de **exclusão mútua**. Temos um recurso (a impressora) que não pode ser usado por dois processos ao mesmo tempo. A seção crítica (SC) do nosso código é o bloco onde o cliente envia a mensagem para a impressora e espera a resposta. Precisamos garantir que apenas um cliente esteja nesta SC por vez.

Como o servidor é "burro", toda a coordenação deve ser feita exclusivamente entre os clientes (de forma *peer-to-peer*).

### 2.2. A Comunicação: gRPC

O "como" da comunicação é feito com gRPC. Definimos nossos serviços e mensagens no arquivo `.proto`:

* `PrintingService`: Usado pelo Cliente para falar com o Servidor Burro.
* `MutualExclusionService`: Usado pelos Clientes para falarem entre si (`RequestAccess`).

### 2.3. A Ordenação: Relógios Lógicos de Lamport

Em um sistema distribuído, não há relógio global. Para que o algoritmo de Ricart-Agrawala funcione, precisamos de uma forma de saber qual requisição "veio primeiro".

* **Por quê?** Para desempatar requisições concorrentes.
* **Como?** Cada cliente mantém um `AtomicLong lamportClock`.
    1.  **Regra 1 (Evento Local):** Antes de qualquer evento (ex: pedir acesso, enviar resposta), o cliente incrementa seu relógio: `tick()`.
    2.  **Regra 2 (Receber Mensagem):** Ao receber *qualquer* mensagem (seja `RequestAccess` ou `AccessResponse`), o cliente atualiza seu relógio: `local = max(local, recebido) + 1`.

Isso garante uma **ordem causal** de eventos.

### 2.4. A Solução: Algoritmo de Ricart-Agrawala

Este é o coração do "como" da exclusão mútua.

* **Por quê?** É um algoritmo baseado em permissão que garante a exclusão mútua sem um coordenador central.
* **Como?** Cada cliente (`PrintingClient`) mantém um estado:
    * `RELEASED`: Estado normal, não quer acessar a SC.
    * `WANTED`: Quer acessar a SC.
    * `HELD`: Está atualmente na SC.

O fluxo de implementação é o seguinte:

1.  **Entrando em `WANTED` (`requestCriticalSection`):**
    * O cliente muda seu estado para `WANTED`.
    * Ele armazena seu *timestamp* de requisição (`ourRequestTimestamp = tick()`) e um número de requisição (para desempate).
    * Ele envia uma mensagem `RequestAccess(timestamp)` para **todos** os outros clientes (pares).
    * Ele espera (usando um `CountDownLatch`) até receber uma resposta `AccessResponse` ("GRANT") de **todos** os pares.

2.  **Recebendo um `RequestAccess` (`MutualExclusionServiceImpl`):**
    * Quando um Cliente A recebe um `RequestAccess` de um Cliente B:
    * **Caso 1: A está `RELEASED`:** A responde "GRANT" imediatamente.
    * **Caso 2: A está `HELD`:** A **não responde**. Ele adia a resposta (coloca o ID de B na fila `deferredReplies`) e B fica esperando.
    * **Caso 3: A está `WANTED`:** Este é o desempate.
        * A compara seu *timestamp* de requisição com o de B.
        * Se o *timestamp* de A for **menor** (ou igual, mas seu ID for menor), A "vence". Ele **não responde** para B e B fica esperando.
        * Se o *timestamp* de B for **menor**, B "vence". A responde "GRANT" imediatamente para B.

3.  **Entrando em `HELD` (`accessCriticalSection`):**
    * Quando o `CountDownLatch` (do passo 1) chega a zero, o cliente sabe que tem permissão de todos.
    * Ele muda seu estado para `HELD`.
    * **Esta é a Seção Crítica:** Ele envia a mensagem `SendToPrinter` para o Servidor Burro e aguarda a resposta.

4.  **Saindo da SC (`releaseCriticalSection`):**
    * Após a impressora responder, o cliente muda seu estado de volta para `RELEASED`.
    * Ele então envia as respostas "GRANT" para todos os clientes que estavam em sua fila `deferredReplies`.
    * Isso "libera" o próximo cliente (que estava esperando) para entrar na SC.

## 3. Arquitetura do Código

* **`pom.xml`**: Configura o projeto Maven, importa as dependências do gRPC e, crucialmente, usa o `maven-shade-plugin` para construir um "fat JAR" que inclui todas as dependências (como o `io.grpc.*`).
* **`printing.proto`**: O contrato gRPC. Define a `java_package` para `br.com.paralela.tp1.grpc`.
* **`PrinterServer.java`**: O "Servidor Burro". Apenas inicia um servidor gRPC e anexa o `PrintingServiceImpl`.
* **`PrintingServiceImpl.java`**: A lógica do "Servidor Burro". Implementa `sendToPrinter` com um `Thread.sleep` de 2-3 segundos.
* **`PrintingClient.java`**: O "Cliente Inteligente". Contém a máquina de estados (`State`), o relógio de Lamport (`lamportClock`), a lógica de `request/access/releaseCriticalSection`, e o `main` que inicia tudo (incluindo seu próprio servidor gRPC).
* **`MutualExclusionServiceImpl.java`**: A lógica de **servidor** do Cliente Inteligente. É esta classe que implementa `requestAccess` e contém a lógica de decisão (Casos 1, 2 e 3) de Ricart-Agrawala.

## 4. Como Executar o Projeto

Siga estes passos para compilar e executar a simulação.

### 4.1. Pré-requisitos

Você precisa ter o **Java Development Kit (JDK 11+)** e o **Apache Maven** instalados e configurados no seu PATH.

* Verifique o Java: `java -version`
* Verifique o Maven: `mvn -version`

### 4.2. Compilando o Projeto (Build)

1.  Abra um terminal na pasta raiz do projeto (ex: `.../tp1/`).
2.  Execute o comando do Maven para limpar, compilar, gerar código gRPC e empacotar tudo em um "fat JAR":

    ```bash
    mvn clean package
    ```
3.  Isso criará o arquivo `target/distributed-printing-1.0.0.jar`. Este é o único arquivo que você precisa para executar.

### 4.3. Executando a Simulação

Você precisará de **4 janelas de terminal** abertas. Todas devem estar no mesmo diretório raiz do projeto (ex: `.../tp1/`).

**Terminal 1: Servidor "Burro"**
*Inicie o servidor de impressão primeiro. Ele ficará aguardando conexões.*
```bash
java -cp target/distributed-printing-1.0.0.jar br.com.paralela.tp1.PrinterServer --port=50051
```

**Terminal 1: Servidor "Burro"**
*Inicie o servidor de impressão primeiro. Ele ficará aguardando conexões.*
```bash
java -cp target/distributed-printing-1.0.0.jar br.com.paralela.tp1.PrinterServer --port=50051
```

*Saída esperada: `--- Servidor de Impressão 'Burro' iniciado na porta 50051 ---`*

**Terminal 2: Cliente 1**
*Inicia o cliente com ID=1 na porta 50052, informando quem são seus pares (2 e 3).*

```bash
java -cp target/distributed-printing-1.0.0.jar br.com.paralela.tp1.PrintingClient --id 1 --port 50052 --server localhost:50051 --peer 2:localhost:50053 --peer 3:localhost:50054
```

**Terminal 3: Cliente 2**
*Inicia o cliente com ID=2 na porta 50053, informando quem são seus pares (1 e 3).*

```bash
java -cp target/distributed-printing-1.0.0.jar br.com.paralela.tp1.PrintingClient --id 2 --port 50053 --server localhost:50051 --peer 1:localhost:50052 --peer 3:localhost:50054
```

**Terminal 4: Cliente 3**
*Inicia o cliente com ID=3 na porta 50054, informando quem são seus pares (1 e 2).*

```bash
java -cp target/distributed-printing-1.0.0.jar br.com.paralela.tp1.PrintingClient --id 3 --port 50054 --server localhost:50051 --peer 1:localhost:50052 --peer 2:localhost:50053
```

### 4.4. Conclusão da Execução

Após alguns segundos, os clientes começarão a disputar o acesso. Você verá logs nos terminais dos clientes (ex: `MODO: WANTED`, `DEFERINDO resposta para...`, `MODO: HELD`).

No **Terminal 1 (Servidor)**, você verá o resultado final: as mensagens de impressão aparecendo **em ordem, uma de cada vez**, provando que a exclusão mútua está funcionando.

Abaixo está um exemplo de saída do servidor:

```text
Aguardando requisições de impressão...
IMPRIMINDO... [TS: 6] CLIENTE 1: Trabalho de SD - Cliente 1 imprimindo. (Req: 1)
IMPRESSÃO CONCLUÍDA. Respondendo ao Cliente 1
IMPRIMINDO... [TS: 10] CLIENTE 2: Trabalho de SD - Cliente 2 imprimindo. (Req: 1)
IMPRESSÃO CONCLUÍDA. Respondendo ao Cliente 2
IMPRIMINDO... [TS: 18] CLIENTE 3: Trabalho de SD - Cliente 3 imprimindo. (Req: 1)
IMPRESSÃO CONCLUÍDA. Respondendo ao Cliente 3
IMPRIMINDO... [TS: 22] CLIENTE 1: Trabalho de SD - Cliente 1 imprimindo. (Req: 2)
IMPRESSÃO CONCLUÍDA. Respondendo ao Cliente 1
```

Para parar a simulação, vá em cada um dos 4 terminais e pressione `Ctrl + C`.

