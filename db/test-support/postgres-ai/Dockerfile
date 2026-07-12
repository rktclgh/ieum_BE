FROM --platform=linux/amd64 pgvector/pgvector:0.8.1-pg16-bookworm@sha256:16e62164a405447dca191079a924ee5b8a9dbf04fe53128701ffbea857b37782 AS pgvector

RUN mkdir -p /tmp/pgvector/lib /tmp/pgvector/extension \
	&& cp -L /usr/lib/postgresql/16/lib/vector.so /tmp/pgvector/lib/vector.so \
	&& cp -L /usr/share/postgresql/16/extension/vector* /tmp/pgvector/extension/ \
	&& test -s /tmp/pgvector/lib/vector.so

FROM --platform=linux/amd64 postgis/postgis:16-3.4

COPY --from=pgvector /tmp/pgvector/lib/vector.so /usr/lib/postgresql/16/lib/vector.so
COPY --from=pgvector /tmp/pgvector/extension/vector* /usr/share/postgresql/16/extension/

RUN test -f /usr/lib/postgresql/16/lib/vector.so \
	&& test -f /usr/share/postgresql/16/extension/vector.control
