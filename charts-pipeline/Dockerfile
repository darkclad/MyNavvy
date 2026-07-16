# One image with both GDAL (reads NOAA S-57 ENCs) and tippecanoe (builds MBTiles).
FROM ghcr.io/osgeo/gdal:ubuntu-small-latest

RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential libsqlite3-dev zlib1g-dev git curl unzip ca-certificates \
    && git clone --depth 1 https://github.com/felt/tippecanoe.git /tmp/tippecanoe \
    && make -C /tmp/tippecanoe -j"$(nproc)" \
    && make -C /tmp/tippecanoe install \
    && rm -rf /tmp/tippecanoe /var/lib/apt/lists/*

COPY build_charts.sh /build_charts.sh
# Strip any CRLF (script may be edited on Windows) so bash doesn't choke on \r.
RUN sed -i 's/\r$//' /build_charts.sh && chmod +x /build_charts.sh

ENTRYPOINT ["/build_charts.sh"]
