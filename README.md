# Rinha de Backend - Recriando o PIX - Java 21 / Spring Boot

Implementação em Java 21 + Spring Boot 3.3 + JDBC puro (sem Hibernate) sobre PostgreSQL.

## Como funciona

- **API** (`com.rinha.web`): recebe `POST /transfers`, grava a transferência como
  `pending` numa única inserção (com `ON CONFLICT (idempotency_key) DO NOTHING`
  para a idempotência ser resolvida sem race condition) e responde na hora —
  não espera o worker.
- **Worker** (`com.rinha.worker.SettlementWorker`): roda no mesmo processo,
  como pede o desafio para linguagens com runtime "vivo" entre requisições.
  Usa um pool de **virtual threads** (novidade do Java 21/Loom) consumindo
  uma fila em memória; cada liquidação é uma transação JDBC que:
  1. dá `SELECT ... FOR UPDATE` na própria linha da transferência (evita
     liquidar a mesma duas vezes);
  2. trava as duas contas envolvidas **sempre na mesma ordem global**
     (por `id`, lexicograficamente) — é isso que evita deadlock em cadeias
     circulares e fan-in quando várias transferências disputam as mesmas
     contas ao mesmo tempo;
  3. só então confere o saldo e decide `completed` ou `failed`.
  Há também uma varredura periódica (`@Scheduled`, a cada 500ms) que
  reenfileira qualquer `pending` esquecido — cobre reinício do processo.

Sem Hibernate/JPA de propósito: no meio de 200 requisições concorrentes no
mesmo carinho de saldo, controlar exatamente o SQL e o momento de cada lock
é mais previsível do que confiar em locking otimista/gerenciamento de sessão
de um ORM.

## Rodar localmente

```bash
cd participants/java
docker compose up --build
```

```bash
curl http://localhost:3005/health

curl -X POST http://localhost:3005/accounts \
  -H "Content-Type: application/json" \
  -d '{"id": "acc-1", "balance": 100000}'

curl -X POST http://localhost:3005/accounts \
  -H "Content-Type: application/json" \
  -d '{"id": "acc-2", "balance": 0}'

curl -X POST http://localhost:3005/transfers \
  -H "Content-Type: application/json" \
  -d '{"payerId": "acc-1", "payeeId": "acc-2", "amount": 2500, "idempotencyKey": "abc-123"}'

curl http://localhost:3005/transfers/<id>
curl http://localhost:3005/accounts/acc-1/statement
```

## Rodar a bateria de corretude

```bash
./scripts/test-local.sh java
# ou, com o container já no ar:
cd tests/correctness
API_URL=http://localhost:3005 npx vitest run --reporter=verbose
```

## Sobre `DATABASE_URL`

O `DataSourceConfig` aceita tanto `jdbc:postgresql://...` quanto o formato
`postgres://user:pass@host:5432/db` — se a `DATABASE_URL` do ambiente vier
num formato diferente do usado aqui, ajuste `DataSourceConfig.java`.

## Ajustes de performance já feitos

- `spring.threads.virtual.enabled=true`: cada requisição HTTP roda numa
  virtual thread — bloquear em JDBC não consome uma thread de plataforma.
- HikariCP com pool moderado (32 conexões máx.) para não sufocar o Postgres,
  que só tem 0.5 CPU / 1GB.
- JDBC puro com `RowMapper`s manuais — sem reflection/proxy do Hibernate no
  caminho quente.
- Índices em `transfers(status, created_at)` (para o worker) e em
  `transfers(payer_id/payee_id, created_at) WHERE status = 'completed'`
  (para o extrato).

## Coisas para ajustar antes de rodar contra o orquestrador real

Este projeto foi montado a partir da especificação do desafio (não a partir
do `docker-compose.yml`/`init.sql` originais do repositório, que eu não
tinha em mãos). Antes de comparar com as outras linguagens, confira:

- se o `init.sql` daqui bate com o `init.sql` real do repo (schema já
  fornecido, não deveria ser criado por cada participante);
- o formato exato de `DATABASE_URL` que o orquestrador injeta;
- a porta local mapeada no `docker-compose.yml` (usei `3005`, já que
  `3001-3004` estão ocupadas pelas outras linguagens).
