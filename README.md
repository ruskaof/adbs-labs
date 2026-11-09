# ЭнергоСбыт · Лабораторная работа №1

Сервис на Java (Spring Boot) принимает показания счётчиков и выдаёт данные о собственниках, объектах, тарифах и счетах. Данные и история изменений хранятся в одном экземпляре ClickHouse. Генерация и загрузка CSV выполняются отдельным Python-скриптом.

Приложение находится в `app/`, генератор — в `scripts/dataset.py`, схема — в `sql/schema.sql`, CSV — в `data/`.

## Запуск

Понадобятся Docker с Compose, GNU Make и Python 3.10+. Команды выполняются из корня репозитория.

~~~sh
make run
make setup
curl -i http://localhost:8080/ping
curl http://localhost:8123/ping
~~~

Оба запроса возвращают HTTP 200: приложение — пустой ответ, ClickHouse — `Ok.`.
Настройки подключения и портов задаются в `.env.local`. Описание API: [api/openapi.yaml](api/openapi.yaml).

| Команда | Назначение |
| --- | --- |
| `make run` | Собрать и запустить сервисы. |
| `make gen` | Создать семь CSV в `data/`. |
| `make load` | Загрузить CSV в существующие пустые таблицы. |
| `make setup` | Создать таблицы, сгенерировать и загрузить данные. |
| `make teardown` | Удалить семь таблиц лабораторной вместе с данными. |
| `make stop` | Остановить контейнеры, сохранив данные в `volume/`. |

Повторная загрузка в непустые таблицы отклоняется, в том числе после частичного импорта. Для сброса данных выполните `make teardown`, затем `make setup`.

## Схема

| Таблица | Ключ сортировки | Причина |
| --- | --- | --- |
| dim_realty, dim_gauges | id | Точечный поиск объекта или счётчика. |
| dim_users | id | Одна актуальная строка на собственника. |
| dim_tariffs | (id, valid_from) | Один ключ на интервал тарифа. Версии с разными датами начала сохраняются. |
| fact_gauge_bindings | (realty_id, gauge_id) | Поиск по объекту. Установка и отмена совпадают по ключу и версии. |
| fact_meter_readings | (realty_id, reading_date) | Поиск по объекту и диапазону дат. Корректировка попадает в тот же ключ и ту же месячную партицию. |
| fact_invoice | (realty_id, created_at) | Поиск и сортировка счетов внутри объекта. |

Самая большая таблица, `fact_meter_readings`, разделена по месяцам для выборок по датам. Остальные таблицы не партиционируются из-за небольшого объёма.

## Проверка сценариев

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
SELECT fio, phone, phone_prev FROM dim_users WHERE id = 1;
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
