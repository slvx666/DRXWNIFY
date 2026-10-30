-- Одна строка на посетителя, день и событие; n — сколько раз за день.
CREATE TABLE hits (
  day     TEXT    NOT NULL,            -- YYYY-MM-DD, UTC
  kind    TEXT    NOT NULL,            -- view | apk | pc
  visitor TEXT    NOT NULL,            -- случайный id из localStorage посетителя
  n       INTEGER NOT NULL DEFAULT 1,
  PRIMARY KEY (day, kind, visitor)
) WITHOUT ROWID;
