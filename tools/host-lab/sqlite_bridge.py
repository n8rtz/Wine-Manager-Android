#!/usr/bin/env python3
"""Persistent SQLite bridge for the host Lab replay harness.

Mirrors ProducerGazetteer.lookup / lookupWines (expanded, source-tracked
variant) SQL-for-SQL against the packaged SQLite assets, so the real Kotlin
ProducerResolver/LinkedWineResolver run on the JVM without an Android
SQLite driver. Protocol: one JSON request per stdin line, one JSON reply
per stdout line. All reply values are strings.

Requests:
  {"op":"producer","key":"...","limit":256}
  {"op":"wine","key":"...","producerIds":["..."],"limit":200}
Replies:
  {"rows":[["id","name","key","source","country"], ...]}
  {"rows":[["id","name","key","producer_id","lwin7"], ...]}
  {"error":"..."}
"""
import json
import sqlite3
import sys


def trigrams(key: str):
    grams = [key[i:i + 3] for i in range(len(key) - 2)] if len(key) >= 3 else []
    seen = []
    for gram in grams:
        if not any(ch.isspace() for ch in gram) and gram not in seen:
            seen.append(gram)
    return seen[:24]


def producer_lookup(db, key, limit):
    exact = db.execute(
        "SELECT p.id,p.name,p.key,COALESCE(s.source,'lwin'),COALESCE(p.country_group,'') "
        "FROM producers p LEFT JOIN producer_sources s ON s.id=p.id "
        "WHERE p.key=? ORDER BY p.id LIMIT ?", (key, limit)).fetchall()
    if exact:
        return exact
    grams = trigrams(key)
    if not grams:
        return []
    query = " OR ".join(f'"{g}"' for g in grams)
    return db.execute(
        "SELECT p.id,p.name,p.key,COALESCE(s.source,'lwin'),COALESCE(p.country_group,'') "
        "FROM names JOIN producers p ON names.id=p.id "
        "LEFT JOIN producer_sources s ON s.id=p.id "
        "WHERE names MATCH ? ORDER BY bm25(names),p.id LIMIT ?", (query, limit)).fetchall()


def wine_lookup(db, key, producer_ids, limit):
    ids = sorted(producer_ids)
    slots = ",".join("?" for _ in ids)

    def run(fuzzy, arg):
        source = "wine_fts JOIN wine_names n ON wine_fts.rowid=n.id" if fuzzy else "wine_names n"
        predicate = "wine_fts MATCH ?" if fuzzy else "n.key=?"
        order = "bm25(wine_fts)," if fuzzy else ""
        sql = (f"SELECT n.id,n.name,n.key,p.producer_id,l.lwin7 FROM {source} "
               f"JOIN wine_links l ON l.wine_id=n.id JOIN wine_producers p ON p.id=l.producer_id "
               f"WHERE {predicate} AND p.producer_id IN ({slots}) "
               f"ORDER BY {order}n.id,p.producer_id LIMIT ?")
        return db.execute(sql, (arg, *ids, limit)).fetchall()

    exact = run(False, key)
    if exact:
        return exact
    grams = trigrams(key)
    if not grams:
        return []
    return run(True, " OR ".join(f'"{g}"' for g in grams))


def handle(producers, wines, line):
    req = json.loads(line)
    if req["op"] == "producer":
        rows = producer_lookup(producers, req["key"], int(req["limit"]))
    elif req["op"] == "wine":
        rows = wine_lookup(wines, req["key"], req["producerIds"], int(req["limit"]))
    else:
        raise ValueError(f"unknown op {req['op']}")
    return {"rows": [["" if v is None else str(v) for v in row] for row in rows]}


def main():
    producers = sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True, check_same_thread=False)
    wines = sqlite3.connect(f"file:{sys.argv[2]}?mode=ro", uri=True, check_same_thread=False)
    if len(sys.argv) > 4 and sys.argv[3] == "--serve":
        # JVM child processes are blocked in the host sandbox, so the shell
        # driver starts this server and the Kotlin runner connects by socket.
        import socket
        server = socket.socket()
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("127.0.0.1", 0))
        server.listen(1)
        with open(sys.argv[4], "w") as port_file:
            port_file.write(str(server.getsockname()[1]))
        conn, _ = server.accept()
        stream = conn.makefile("rw", encoding="utf-8", newline="\n")
        for line in stream:
            try:
                reply = handle(producers, wines, line)
            except Exception as exc:
                reply = {"error": f"{type(exc).__name__}: {exc}"}
            stream.write(json.dumps(reply, ensure_ascii=True) + "\n")
            stream.flush()
        return
    for line in sys.stdin:
        if not line.strip():
            continue
        try:
            reply = handle(producers, wines, line)
        except Exception as exc:  # bridge must never die mid-run
            reply = {"error": f"{type(exc).__name__}: {exc}"}
        sys.stdout.write(json.dumps(reply, ensure_ascii=True) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
