#!/usr/bin/env python3
"""
Select NOAA ENC cells intersecting a circle (center + radius) from the ENC product catalog.

  select_cells.py <ENCProdCat_19115.xml> <lat> <lon> <radius_nm>

Output: one line per cell, tab-separated:  CELL \t URL \t REVISION_DATE \t W \t S \t E \t N
(the cell's own bbox — make_synthetic.py unions these to find the edge of NOAA coverage)

Catalog facts (verified 2026-07-16 against the live file):
  - ~7,200 DS_DataSet entries, every one status "completed".
  - Cell name  = MD_DataIdentification > citation > CI_Citation > title.
  - Extent     = EX_BoundingPolygon with gml:pos "LAT LON" pairs (note: LAT first!),
                 longitudes may be shifted across the antimeridian (e.g. -220 == +140).
  - Download   = distributionInfo > ... > linkage > URL  (https://www.charts.noaa.gov/ENCs/<CELL>.zip)
The 50 MB file is parsed with iterparse, namespace-agnostic (localname matching).
"""
import sys, math
import xml.etree.ElementTree as ET


def local(tag):
    return tag.rsplit('}', 1)[-1]


def norm_lon(lon):
    while lon < -180.0:
        lon += 360.0
    while lon > 180.0:
        lon -= 360.0
    return lon


def main():
    cat_path, lat_s, lon_s, rad_s = sys.argv[1:5]
    clat, clon, rad_nm = float(lat_s), float(lon_s), float(rad_s)

    # circle -> query bbox in degrees
    dlat = rad_nm / 60.0
    dlon = rad_nm / (60.0 * max(0.1, math.cos(math.radians(clat))))
    qw, qe = clon - dlon, clon + dlon
    qs, qn = clat - dlat, clat + dlat

    picked = []
    # per-dataset accumulators
    title = None
    url = None
    rev = None
    lats, lons = [], []
    in_citation_title = False

    for event, el in ET.iterparse(cat_path, events=("start", "end")):
        tag = local(el.tag)
        if event != "end":
            continue
        if tag == "title" and title is None:
            # first CharacterString under the dataset's citation title
            for ch in el.iter():
                if local(ch.tag) == "CharacterString" and ch.text:
                    title = ch.text.strip()
                    break
        elif tag == "pos" and el.text:
            parts = el.text.split()
            if len(parts) >= 2:
                lats.append(float(parts[0]))
                lons.append(norm_lon(float(parts[1])))
        elif tag == "URL" and el.text and url is None:
            url = el.text.strip()
        elif tag == "Date" and el.text and rev is None:
            rev = el.text.strip()
        elif tag == "DS_DataSet":
            if title and lats and lons:
                w, e = min(lons), max(lons)
                s, n = min(lats), max(lats)
                # guard: cells genuinely spanning the antimeridian produce a bogus wide
                # bbox after normalization; treat >180-degree spans as two halves.
                boxes = [(w, s, e, n)] if (e - w) <= 180.0 else [(-180.0, s, e, n), (w, s, 180.0, n)]
                for (bw, bs, be, bn) in boxes:
                    if bw <= qe and be >= qw and bs <= qn and bn >= qs:
                        picked.append((title, url or "", rev or "", bw, bs, be, bn))
                        break
            title = None
            url = None
            rev = None
            lats, lons = [], []
            el.clear()  # keep memory flat on the 50 MB file

    picked.sort()
    for name, u, r, w, s, e, n in picked:
        if not u:
            u = f"https://www.charts.noaa.gov/ENCs/{name}.zip"
        sys.stdout.write(f"{name}\t{u}\t{r}\t{w:.6f}\t{s:.6f}\t{e:.6f}\t{n:.6f}\n")
    sys.stderr.write(f"selected {len(picked)} cells for ({clat:.4f},{clon:.4f}) r={rad_nm}nm "
                     f"bbox=({qw:.3f},{qs:.3f},{qe:.3f},{qn:.3f})\n")


if __name__ == "__main__":
    main()
