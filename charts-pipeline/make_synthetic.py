#!/usr/bin/env python3
"""
Synthetic foreign chart tier: fill the part of a region bbox that NOAA ENCs don't cover
(Mexico / Canada waters) with DEPARE-like depth areas built from FREE bathymetry, plus OSM
coastline and OSM/OpenSeaMap seamarks. Everything is appended into the SAME charts.gpkg layers
the NOAA extraction produced, tagged SYN=1 / INTU=2, so tippecanoe, the style, the depth HUD
and the routing grid all just work.

  Sources (no free official ENCs exist for either country — researched 2026-07):
    - Mexico (and generic fallback): GEBCO global 15-arcsec bathymetry grid (free download).
    - Canada: CHS NONNA-10 bathymetry via its public WCS (Open Government Licence).
    - Coastline: OSM land polygons (osmdata.openstreetmap.de, split 4326).
    - Buoys/lights: OSM seamark nodes via Overpass.

  Usage:
    make_synthetic.py --gpkg charts.gpkg --bbox W,S,E,N --cells cells.tsv \
                      --cache /root/mynavvy_work/cache --work <region work dir> --meta <meta out>

  Writes into the gpkg: DEPARE (+DRVAL1/DRVAL2), DEPCNT (+VALDCO), LNDARE, COALNE, BOYLAT,
  LIGHTS (all SYN=1, INTU=2) and a new DATLIM line layer (the official-data-limit, drawn as a
  dashed magenta line by the app style). Writes a shell-evalable meta file:
  SYN_MX=0/1 SYN_CA=0/1 GEBCO_VER=... NONNA_VER=...
"""
import argparse, json, math, os, subprocess, sys, urllib.request, urllib.parse, zipfile
from osgeo import gdal, ogr, osr

gdal.UseExceptions()
ogr.UseExceptions()

# Depth-band edges in metres (down positive). DRVAL1=lo, DRVAL2=hi like S-57 DEPARE.
BAND_EDGES = [0, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 6000]
CANADA_LAT = 41.0        # foreign water north of this tries NONNA (Canada), else GEBCO
MIN_FOREIGN_DEG2 = 0.02  # ignore slivers (catalog bbox jitter at the border)
SYN_INTU = 2             # synthetic sits above NOAA band 1 (coarse overview) but below bands 2-6

GEBCO_URLS = [
    # tried in order; override with GEBCO_URL env. The global grid is a one-time ~7.5 GB download,
    # cached forever in --cache. (The old bodc.ac.uk open_download path 404s — CEDA is the
    # published archive for GEBCO_2025, verified 2026-07-16.)
    os.environ.get("GEBCO_URL", ""),
    "https://dap.ceda.ac.uk/bodc/gebco/global/gebco_2025/ice_surface_elevation/netcdf/gebco_2025.zip?download=1",
    "https://dap.ceda.ac.uk/bodc/gebco/global/gebco_2024/ice_surface_elevation/netcdf/gebco_2024.zip?download=1",
]
NONNA_WCS = os.environ.get(
    "NONNA_WCS", "https://nonna-geoserver.data.chs-shc.ca/geoserver/wcs")
LAND_POLY_URL = "https://osmdata.openstreetmap.de/download/land-polygons-split-4326.zip"
OVERPASS = ["https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"]

WGS84 = osr.SpatialReference()
WGS84.ImportFromEPSG(4326)
try:
    WGS84.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)
except AttributeError:
    pass


def log(msg):
    print(f"[synthetic] {msg}", flush=True)


def bbox_poly(w, s, e, n):
    ring = ogr.Geometry(ogr.wkbLinearRing)
    for x, y in [(w, s), (e, s), (e, n), (w, n), (w, s)]:
        ring.AddPoint_2D(x, y)
    p = ogr.Geometry(ogr.wkbPolygon)
    p.AddGeometry(ring)
    return p


def noaa_coverage(cells_tsv):
    """Union of the selected NOAA cells' bboxes, usage bands >= 2 (band 1 is a world-scale
    overview whose bbox says nothing about surveyed coverage)."""
    union = ogr.Geometry(ogr.wkbMultiPolygon)
    with open(cells_tsv) as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 7:
                continue
            name = parts[0]
            band = name[2] if len(name) > 2 else "0"
            if band not in "23456":
                continue
            w, s, e, n = (float(x) for x in parts[3:7])
            union.AddGeometry(bbox_poly(w, s, e, n))
    return union.UnionCascaded() if union.GetGeometryCount() else ogr.Geometry(ogr.wkbPolygon)


def http_download(url, dest, desc):
    log(f"downloading {desc}: {url}")
    tmp = dest + ".part"
    req = urllib.request.Request(url, headers={"User-Agent": "MyNavvy-pipeline"})
    with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
    os.replace(tmp, dest)
    log(f"  -> {dest} ({os.path.getsize(dest)/1048576:.0f} MB)")


def ensure_gebco(cache):
    """Global GEBCO grid, cached. Returns (netcdf_path, version_string)."""
    gdir = os.path.join(cache, "gebco")
    os.makedirs(gdir, exist_ok=True)
    for f in sorted(os.listdir(gdir)):
        if f.lower().endswith(".nc"):
            ver = "2025" if "2025" in f else ("2024" if "2024" in f else "?")
            return os.path.join(gdir, f), ver
    for url in [u for u in GEBCO_URLS if u]:
        try:
            z = os.path.join(gdir, "gebco.zip")
            http_download(url, z, "GEBCO global grid (one-time)")
            with zipfile.ZipFile(z) as zf:
                nc = [m for m in zf.namelist() if m.lower().endswith(".nc")]
                if not nc:
                    continue
                zf.extract(nc[0], gdir)
            os.remove(z)
            path = os.path.join(gdir, nc[0])
            ver = "2025" if "2025" in url + nc[0] else ("2024" if "2024" in url + nc[0] else "?")
            return path, ver
        except Exception as ex:
            log(f"GEBCO fetch failed from {url}: {ex}")
    raise RuntimeError("no GEBCO grid available (set GEBCO_URL or drop the .nc into cache/gebco/)")


def gebco_subset(cache, w, s, e, n, out_tif):
    nc, ver = ensure_gebco(cache)
    src = gdal.Open(f'NETCDF:"{nc}":elevation') if "NETCDF" not in nc else gdal.Open(nc)
    if src is None:
        src = gdal.Open(nc)
    gdal.Translate(out_tif, src, projWin=[w, n, e, s])
    return ver


def nonna_subset(w, s, e, n, out_tif):
    """CHS NONNA-10 via its public WCS, downsampled to ~50 m and warped to EPSG:4326.
    VERIFIED against the live GeoServer 2026-07-16: the coverage is stored in EPSG:3857 with
    subset axes `x`/`y` (mercator metres — Long/Lat 404s) and scale axes `i`/`j`
    (x/y raises ScaleAxisUndefined). Values: elevation, sea NEGATIVE, drying heights positive,
    nodata 3.4e38, valid only where surveyed. Any failure raises — caller falls back to GEBCO."""
    cap_url = f"{NONNA_WCS}?service=WCS&version=2.0.1&request=GetCapabilities"
    with urllib.request.urlopen(cap_url, timeout=60) as r:
        caps = r.read().decode("utf-8", "replace")
    import re
    ids = re.findall(r"<wcs:CoverageId>([^<]+)</wcs:CoverageId>", caps)
    cov = next((i for i in ids if "10" in i and "nonna" in i.lower()), None) or \
        next((i for i in ids if "nonna" in i.lower()), None)
    if not cov:
        raise RuntimeError(f"no NONNA coverage id in WCS capabilities ({len(ids)} ids)")
    log(f"NONNA WCS coverage: {cov}")

    def merc(lon, lat):
        r = 20037508.342789244
        return lon * r / 180.0, math.log(math.tan((90 + lat) * math.pi / 360.0)) / math.pi * r
    x0, y0 = merc(w, s)
    x1, y1 = merc(e, n)
    px = min(4000, max(200, int((e - w) * 2220)))   # ~50 m/px
    py = min(4000, max(200, int((n - s) * 2220)))
    url = (f"{NONNA_WCS}?service=WCS&version=2.0.1&request=GetCoverage"
           f"&coverageId={urllib.parse.quote(cov)}&format=image/geotiff"
           f"&subset=x({x0:.0f},{x1:.0f})&subset=y({y0:.0f},{y1:.0f})"
           f"&scaleSize=i({px}),j({py})")
    raw = out_tif + ".3857.tif"
    http_download(url, raw, "NONNA-10 WCS subset")
    if gdal.Open(raw) is None:
        raise RuntimeError("NONNA WCS returned no readable GeoTIFF")
    gdal.Warp(out_tif, raw, dstSRS="EPSG:4326")
    os.remove(raw)
    return "NONNA-10"


def ensure_land_polys(cache):
    d = os.path.join(cache, "land-polygons-split-4326")
    shp = os.path.join(d, "land_polygons.shp")
    if os.path.exists(shp):
        return shp
    z = os.path.join(cache, "land-polygons.zip")
    if not os.path.exists(z):
        http_download(LAND_POLY_URL, z, "OSM land polygons (one-time)")
    with zipfile.ZipFile(z) as zf:
        zf.extractall(cache)
    if not os.path.exists(shp):
        raise RuntimeError(f"land polygons zip did not contain {shp}")
    return shp


def field(layer, name, ftype=ogr.OFTReal):
    if layer.FindFieldIndex(name, 1) < 0:
        layer.CreateField(ogr.FieldDefn(name, ftype))


def add_feature(layer, geom, **attrs):
    fd = ogr.Feature(layer.GetLayerDefn())
    fd.SetGeometry(geom)
    for k, v in attrs.items():
        if v is not None:
            fd.SetField(k, v)
    layer.CreateFeature(fd)


def append_polys_from_mask(gpkg_layer, mask_ds, foreign, drval1, drval2):
    """Polygonize the 1-valued pixels of mask_ds band 1 and append clipped polygons."""
    drv = ogr.GetDriverByName("Memory")
    mem = drv.CreateDataSource("m")
    lyr = mem.CreateLayer("p", srs=WGS84, geom_type=ogr.wkbPolygon)
    lyr.CreateField(ogr.FieldDefn("v", ogr.OFTInteger))
    band = mask_ds.GetRasterBand(1)
    gdal.Polygonize(band, band, lyr, 0)   # band as its own mask: only non-zero pixels
    n = 0
    for f in lyr:
        g = f.GetGeometryRef()
        if g is None:
            continue
        g = g.Clone()
        g = g.Simplify(0.0005)
        try:
            g = g.Intersection(foreign)
        except Exception:
            continue
        if g is None or g.IsEmpty():
            continue
        add_feature(gpkg_layer, g, DRVAL1=float(drval1), DRVAL2=float(drval2),
                    SYN=1, INTU=SYN_INTU)
        n += 1
    return n


def synth_bathy(gpkg, tif, foreign, work, tag):
    """Depth bands + contours from one bathy subset (elevation grid, sea negative)."""
    ds = gdal.Open(tif)
    gt = ds.GetGeoTransform()
    arr = ds.GetRasterBand(1).ReadAsArray()
    nodata = ds.GetRasterBand(1).GetNoDataValue()
    import numpy as np
    a = arr.astype("float64")
    if nodata is not None:
        a[a == nodata] = np.nan
    depth = -a                       # metres below datum, positive down
    depth[~np.isfinite(depth)] = np.nan

    dep_lyr_ds = ogr.Open(gpkg, update=1)
    dep = dep_lyr_ds.GetLayerByName("DEPARE")
    if dep is None:
        raise RuntimeError("charts.gpkg has no DEPARE layer — run the NOAA extraction first")
    for fname, ftype in [("DRVAL1", ogr.OFTReal), ("DRVAL2", ogr.OFTReal),
                         ("SYN", ogr.OFTInteger), ("INTU", ogr.OFTInteger)]:
        field(dep, fname, ftype)

    mem_drv = gdal.GetDriverByName("MEM")
    total = 0
    for lo, hi in zip(BAND_EDGES[:-1], BAND_EDGES[1:]):
        mask = ((depth >= lo) & (depth < hi)).astype("uint8")
        if not mask.any():
            continue
        mds = mem_drv.Create("", ds.RasterXSize, ds.RasterYSize, 1, gdal.GDT_Byte)
        mds.SetGeoTransform(gt)
        mds.SetProjection(ds.GetProjection() or WGS84.ExportToWkt())
        mds.GetRasterBand(1).WriteArray(mask)
        total += append_polys_from_mask(dep, mds, foreign, lo, hi)
    log(f"{tag}: appended {total} synthetic DEPARE polygons")

    # Contours at the band edges -> DEPCNT (VALDCO metres).
    cnt = dep_lyr_ds.GetLayerByName("DEPCNT")
    if cnt is not None:
        field(cnt, "VALDCO", ogr.OFTReal)
        field(cnt, "SYN", ogr.OFTInteger)
        field(cnt, "INTU", ogr.OFTInteger)
        drv = ogr.GetDriverByName("Memory")
        cmem = drv.CreateDataSource("c")
        clyr = cmem.CreateLayer("cont", srs=WGS84, geom_type=ogr.wkbLineString)
        clyr.CreateField(ogr.FieldDefn("id", ogr.OFTInteger))
        clyr.CreateField(ogr.FieldDefn("elev", ogr.OFTReal))
        levels = [-x for x in BAND_EDGES[1:-1]]
        elev_ds = mem_drv.Create("", ds.RasterXSize, ds.RasterYSize, 1, gdal.GDT_Float32)
        elev_ds.SetGeoTransform(gt)
        elev_ds.SetProjection(ds.GetProjection() or WGS84.ExportToWkt())
        import numpy as np2
        filled = a.copy()
        filled[~np.isfinite(filled)] = 9999.0
        elev_ds.GetRasterBand(1).WriteArray(filled.astype("float32"))
        elev_ds.GetRasterBand(1).SetNoDataValue(9999.0)
        gdal.ContourGenerate(elev_ds.GetRasterBand(1), 0, 0, levels, 1, 9999.0, clyr, 0, 1)
        nc = 0
        for f in clyr:
            g = f.GetGeometryRef()
            if g is None:
                continue
            g = g.Clone().Simplify(0.0005)
            try:
                g = g.Intersection(foreign)
            except Exception:
                continue
            if g is None or g.IsEmpty():
                continue
            add_feature(cnt, g, VALDCO=abs(f.GetField("elev")), SYN=1, INTU=SYN_INTU)
            nc += 1
        log(f"{tag}: appended {nc} synthetic DEPCNT contours")
    dep_lyr_ds = None


def synth_land(gpkg, cache, foreign, w, s, e, n):
    shp = ensure_land_polys(cache)
    src = ogr.Open(shp)
    sl = src.GetLayer(0)
    sl.SetSpatialFilterRect(w, s, e, n)
    dst = ogr.Open(gpkg, update=1)
    lnd = dst.GetLayerByName("LNDARE")
    coa = dst.GetLayerByName("COALNE")
    for lyr in (lnd, coa):
        if lyr is not None:
            field(lyr, "SYN", ogr.OFTInteger)
            field(lyr, "INTU", ogr.OFTInteger)
    nl = nc = 0
    for f in sl:
        g = f.GetGeometryRef()
        if g is None:
            continue
        try:
            g = g.Clone().Intersection(foreign)
        except Exception:
            continue
        if g is None or g.IsEmpty():
            continue
        if lnd is not None:
            add_feature(lnd, g, SYN=1, INTU=SYN_INTU)
            nl += 1
        if coa is not None:
            b = g.GetBoundary()
            if b is not None and not b.IsEmpty():
                add_feature(coa, b, SYN=1, INTU=SYN_INTU)
                nc += 1
    log(f"appended {nl} synthetic LNDARE / {nc} COALNE from OSM land polygons")
    dst = None


def synth_seamarks(gpkg, foreign, w, s, e, n):
    q = f"""[out:json][timeout:90];
(
  node["seamark:type"~"^buoy_"]({s},{w},{n},{e});
  node["seamark:type"~"^(light_minor|light_major|landmark|lighthouse)$"]({s},{w},{n},{e});
);
out body;"""
    data = None
    for ep in OVERPASS:
        try:
            req = urllib.request.Request(ep, data=urllib.parse.urlencode({"data": q}).encode(),
                                         headers={"User-Agent": "MyNavvy-pipeline"})
            with urllib.request.urlopen(req, timeout=120) as r:
                data = json.load(r)
            break
        except Exception as ex:
            log(f"Overpass {ep} failed: {ex}")
    if data is None:
        log("seamarks skipped (Overpass unavailable) — depth data unaffected")
        return
    dst = ogr.Open(gpkg, update=1)
    boy = dst.GetLayerByName("BOYLAT")
    lig = dst.GetLayerByName("LIGHTS")
    for lyr in (boy, lig):
        if lyr is not None:
            field(lyr, "SYN", ogr.OFTInteger)
            field(lyr, "INTU", ogr.OFTInteger)
    nb = nl = 0
    for el in data.get("elements", []):
        if el.get("type") != "node":
            continue
        pt = ogr.Geometry(ogr.wkbPoint)
        pt.AddPoint_2D(el["lon"], el["lat"])
        if not pt.Within(foreign):
            continue
        t = el.get("tags", {}).get("seamark:type", "")
        if t.startswith("buoy_") and boy is not None:
            add_feature(boy, pt, SYN=1, INTU=SYN_INTU)
            nb += 1
        elif lig is not None:
            add_feature(lig, pt, SYN=1, INTU=SYN_INTU)
            nl += 1
    log(f"appended {nb} synthetic buoys / {nl} lights from OSM seamarks")
    dst = None


def write_datlim(gpkg, coverage, bbox):
    """Official-data-limit: the NOAA coverage edge, inside the region (not the bbox frame)."""
    dst = ogr.Open(gpkg, update=1)
    lyr = dst.GetLayerByName("DATLIM")
    if lyr is None:
        lyr = dst.CreateLayer("DATLIM", srs=WGS84, geom_type=ogr.wkbMultiLineString)
    field(lyr, "SYN", ogr.OFTInteger)
    inner = bbox.Buffer(-0.02)   # drop segments that coincide with the region frame
    edge = coverage.GetBoundary()
    if edge is None or edge.IsEmpty():
        return
    try:
        edge = edge.Intersection(inner)
    except Exception:
        return
    if edge is None or edge.IsEmpty():
        return
    add_feature(lyr, edge, SYN=1)
    log("wrote DATLIM (official data limit line)")
    dst = None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gpkg", required=True)
    ap.add_argument("--bbox", required=True, help="W,S,E,N")
    ap.add_argument("--cells", required=True)
    ap.add_argument("--cache", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--meta", required=True)
    a = ap.parse_args()

    w, s, e, n = (float(x) for x in a.bbox.split(","))
    bbox = bbox_poly(w, s, e, n)
    coverage = noaa_coverage(a.cells)
    foreign = bbox.Difference(coverage) if not coverage.IsEmpty() else bbox.Clone()
    area = foreign.GetArea() if foreign is not None else 0.0
    meta = {"SYN_MX": 0, "SYN_CA": 0, "GEBCO_VER": "", "NONNA_VER": ""}

    if foreign is None or area < MIN_FOREIGN_DEG2:
        log(f"foreign area {area:.4f} deg^2 — nothing to synthesize")
    else:
        log(f"foreign (non-NOAA) area: {area:.2f} deg^2")
        fw, fe, fs, fn = foreign.GetEnvelope()  # ogr: (minX, maxX, minY, maxY)
        fminx, fmaxx, fminy, fmaxy = fw, fe, fs, fn
        os.makedirs(a.work, exist_ok=True)

        # Canada portion (NONNA) vs everything else (GEBCO), split at CANADA_LAT.
        parts = []
        if fmaxy > CANADA_LAT:
            ca = foreign.Intersection(bbox_poly(fminx, CANADA_LAT, fmaxx, fmaxy))
            if ca and not ca.IsEmpty() and ca.GetArea() >= MIN_FOREIGN_DEG2:
                parts.append(("CA", ca))
        if fminy < CANADA_LAT:
            mx = foreign.Intersection(bbox_poly(fminx, fminy, fmaxx, CANADA_LAT))
            if mx and not mx.IsEmpty() and mx.GetArea() >= MIN_FOREIGN_DEG2:
                parts.append(("MX", mx))

        for tag, geom in parts:
            gw, ge, gs, gn = geom.GetEnvelope()
            tif = os.path.join(a.work, f"synth_bathy_{tag}.tif")
            try:
                if tag == "CA":
                    # GEBCO underlay first (full coverage, coarse), then NONNA-10 on top where
                    # surveyed (features appended later draw later within the same band, so the
                    # better data wins visually). NONNA is patchy (~46% valid in tests).
                    try:
                        gtif = os.path.join(a.work, "synth_bathy_CA_gebco.tif")
                        meta["GEBCO_VER"] = gebco_subset(a.cache, gw, gs, ge, gn, gtif)
                        synth_bathy(a.gpkg, gtif, geom, a.work, "CA-gebco")
                        meta["SYN_CA"] = 1
                    except Exception as ex:
                        log(f"CA GEBCO underlay failed: {ex}")
                    try:
                        meta["NONNA_VER"] = nonna_subset(gw, gs, ge, gn, tif)
                        synth_bathy(a.gpkg, tif, geom, a.work, "CA-nonna")
                        meta["SYN_CA"] = 1
                    except Exception as ex:
                        log(f"NONNA failed ({ex}) — Canada part is GEBCO-only")
                else:
                    meta["GEBCO_VER"] = gebco_subset(a.cache, gw, gs, ge, gn, tif)
                    synth_bathy(a.gpkg, tif, geom, a.work, tag)
                    meta["SYN_MX"] = 1
            except Exception as ex:
                log(f"{tag} bathymetry failed entirely: {ex}")
                continue
            try:
                synth_land(a.gpkg, a.cache, geom, gw, gs, ge, gn)
            except Exception as ex:
                log(f"{tag} land polygons failed: {ex} (water still usable)")
            try:
                synth_seamarks(a.gpkg, geom, gw, gs, ge, gn)
            except Exception as ex:
                log(f"{tag} seamarks failed: {ex}")

        if meta["SYN_MX"] or meta["SYN_CA"]:
            try:
                write_datlim(a.gpkg, coverage, bbox)
            except Exception as ex:
                log(f"DATLIM failed: {ex}")

    with open(a.meta, "w") as f:
        for k, v in meta.items():
            f.write(f'{k}="{v}"\n' if isinstance(v, str) else f"{k}={v}\n")
    log(f"meta -> {a.meta}: {meta}")


if __name__ == "__main__":
    main()
