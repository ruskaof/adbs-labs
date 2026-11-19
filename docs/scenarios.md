# Проверка сценариев

Для SQL-команд откройте клиент ClickHouse из корня проекта.

~~~sh
docker compose --env-file .env.local exec clickhouse sh -c \
  'exec clickhouse-client --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --database "$CLICKHOUSE_DB"'
~~~

### SCD 1 и SCD 3 · собственник

~~~sh
curl -sS -X PATCH http://localhost:8080/users/1 \
  -H 'Content-Type: application/json' -d '{"fio":"Иванов Иван Иванович","phone":"+79111111111"}'
curl -sS -X PATCH http://localhost:8080/users/1 \
  -H 'Content-Type: application/json' -d '{"phone":"+79222222222"}'
curl -sS http://localhost:8080/users/1
~~~

~~~sql
SELECT fio, phone, phone_prev FROM dim_users FINAL WHERE id = 1;
~~~

SQL показывает новое ФИО (SCD 1), текущий телефон +79222222222 и предыдущий +79111111111 (SCD 3).

### SCD 2 · тариф и исторический счёт

~~~sh
curl -sS 'http://localhost:8080/tariffs?date=2025-10-31'
curl -sS 'http://localhost:8080/tariffs?date=2025-11-01'
~~~

~~~sql
SELECT id, price_day_kwph, price_night_kwph, valid_from, valid_to
FROM dim_tariffs FINAL ORDER BY id, valid_from;

SELECT i.created_at, i.price,
       toUInt64(i.day_kw) * t.price_day_kwph + toUInt64(i.night_kw) * t.price_night_kwph AS recalculated
FROM fact_invoice AS i
CROSS JOIN (SELECT * FROM dim_tariffs FINAL) AS t
WHERE i.realty_id = 1 AND toDate(i.created_at) = '2025-10-31'
  AND i.tariff_id = t.id AND t.valid_from <= i.created_at AND i.created_at < t.valid_to;
~~~

С 1 ноября API возвращает новую версию тарифа. Сумма октябрьского счёта совпадает с пересчётом по старой версии.

### Корректировка показания

~~~sh
curl -sS -X POST http://localhost:8080/realty/1/readings \
  -H 'Content-Type: application/json' \
  -d '{"user_id":1,"gauge_id":1,"tariff_id":1,"reading_date":"2026-10-01","day_kw":1250,"night_kw":300}'
curl -sS 'http://localhost:8080/realty/1/readings?date_from=2026-10-01&date_to=2026-10-01'
~~~

~~~sql
SELECT reading_date, day_kw, night_kw, created_at
FROM fact_meter_readings FINAL WHERE realty_id = 1 AND reading_date = '2026-10-01';
~~~

Оба запроса возвращают ровно одну актуальную запись с показаниями 1250 и 300.

### Замена счётчика

~~~sh
curl -sS -X POST http://localhost:8080/realty/1/gauges \
  -H 'Content-Type: application/json' -d '{"gauge_id":2}'
~~~

~~~sql
SELECT gauge_id, sign, version
FROM fact_gauge_bindings FINAL WHERE realty_id = 1;
~~~

При первом запуске примера видна только новая установка счётчика 2 со знаком 1 и версией 2. Строки установки и отмены счётчика 1 схлопываются.

### Удаление ошибочной строки

На свежем датасете добавьте и удалите демонстрационную строку:

~~~sql
INSERT INTO fact_meter_readings
VALUES (1, 1, 1, 1, '2026-11-01', 12500, 300, '2026-11-01 18:00:00');

SELECT count() FROM fact_meter_readings FINAL
WHERE realty_id = 1 AND reading_date = '2026-11-01' AND day_kw = 12500;

ALTER TABLE fact_meter_readings DELETE
WHERE realty_id = 1 AND reading_date = '2026-11-01' AND day_kw = 12500
  AND created_at = '2026-11-01 18:00:00'
SETTINGS mutations_sync = 1;

SELECT count() FROM fact_meter_readings FINAL
WHERE realty_id = 1 AND reading_date = '2026-11-01' AND day_kw = 12500;
~~~

Первый подсчёт возвращает 1, после удаления результат равен 0.
