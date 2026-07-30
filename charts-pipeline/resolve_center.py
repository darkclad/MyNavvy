#!/usr/bin/env python3
"""
Resolve a region center + radius into a padded bbox.

  resolve_center.py <zip|lat,lon> <radius e.g. 300nm|250mi> <geonames_US.txt>

Prints one line of shell-eval'able assignments:
  LAT=.. LON=.. RADIUS_NM=.. XMIN=.. YMIN=.. XMAX=.. YMAX=..

The gazetteer is the GeoNames US postal file (download.geonames.org/export/zip/US.zip -> US.txt,
CC-BY 4.0; tab-separated, no header: country, postalcode, placename, ..., lat[9], lon[10]).
(The Census ZCTA gazetteer moved/404s as of 2026-07 — GeoNames is the stable source.)
Radius default unit is nautical miles; 'mi' = statute miles. bbox gets 10% pad.
"""
import sys, math, re


def main():
    where, radius_s, gaz_path = sys.argv[1:4]

    m = re.fullmatch(r"([\d.]+)\s*(nm|mi)?", radius_s.strip().lower())
    if not m:
        sys.exit(f"bad radius '{radius_s}' (want e.g. 300nm or 250mi)")
    rad = float(m.group(1))
    rad_nm = rad * 0.868976 if m.group(2) == "mi" else rad

    if re.fullmatch(r"-?[\d.]+\s*,\s*-?[\d.]+", where):
        lat_s, lon_s = where.split(",")
        lat, lon = float(lat_s), float(lon_s)
    elif re.fullmatch(r"\d{5}", where):
        lat = lon = None
        with open(gaz_path, encoding="utf-8", errors="replace") as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if len(parts) >= 11 and parts[1] == where:
                    lat = float(parts[9])
                    lon = float(parts[10])
                    break
        if lat is None:
            sys.exit(f"zipcode {where} not found in GeoNames US postal file")
    else:
        sys.exit(f"bad center '{where}' (want 5-digit zip or lat,lon)")

    pad = 1.10
    dlat = rad_nm / 60.0 * pad
    dlon = rad_nm / (60.0 * max(0.1, math.cos(math.radians(lat)))) * pad
    print(f"LAT={lat:.6f} LON={lon:.6f} RADIUS_NM={rad_nm:.1f} "
          f"XMIN={lon - dlon:.4f} YMIN={lat - dlat:.4f} XMAX={lon + dlon:.4f} YMAX={lat + dlat:.4f}")


if __name__ == "__main__":
    main()
