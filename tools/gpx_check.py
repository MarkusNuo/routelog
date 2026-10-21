"""Local check of a routelog GPX file: segments, points, duration, gaps, distance, jumps.
Prints numbers only, never a coordinate or the time of a point, so the output can be shared.
Usage: python tools/gpx_check.py <file.gpx>   (Python 3.11+, standard library only)
"""
import sys
import xml.etree.ElementTree as ET
from datetime import datetime
from math import asin, cos, radians, sin, sqrt

GAP_SECONDS = 2        # routelog records one point per second
JUMP_METERS_PER_SECOND = 70  # about 250 km/h: no car drives that, so it is a GPS glitch


def haversine_m(a, b):
    (lat1, lon1), (lat2, lon2) = a, b
    h = sin(radians(lat2 - lat1) / 2) ** 2 + cos(radians(lat1)) * cos(radians(lat2)) * sin(radians(lon2 - lon1) / 2) ** 2
    return 2 * 6371008.8 * asin(sqrt(h))


def read_segments(path):
    """Returns one list of (lat, lon, datetime) per <trkseg>; points without a time are skipped."""
    segments = []
    for seg in ET.parse(path).getroot().iter():
        if seg.tag.split("}")[-1] != "trkseg":
            continue
        points = []
        for pt in seg:
            when = next((c.text for c in pt if c.tag.split("}")[-1] == "time"), None)
            if when:
                points.append((float(pt.get("lat")), float(pt.get("lon")), datetime.fromisoformat(when.strip())))
        segments.append(points)
    return segments


def main(path):
    segments = [s for s in read_segments(path) if s]
    all_points = [p for s in segments for p in s]
    if not all_points:
        sys.exit("No points with a time found.")
    gaps, jumps, meters = [], 0, 0.0
    for seg in segments:
        for a, b in zip(seg, seg[1:]):
            seconds = (b[2] - a[2]).total_seconds()
            if seconds > GAP_SECONDS:
                gaps.append(seconds)
            step = haversine_m(a[:2], b[:2])
            if seconds > 0 and step / seconds > JUMP_METERS_PER_SECOND:
                jumps += 1  # not driven distance, so it is left out of the sum
            else:
                meters += step
    pauses = [(b[0][2] - a[-1][2]).total_seconds() for a, b in zip(segments, segments[1:])]
    total = (max(p[2] for p in all_points) - min(p[2] for p in all_points)).total_seconds()
    print(f"Segments:     {len(segments)} (points per segment: {', '.join(str(len(s)) for s in segments)})")
    print(f"Points:       {len(all_points)}")
    print(f"Duration:     {int(total // 3600)} h {int(total % 3600 // 60)} min {int(total % 60)} s (first to last point)")
    print(f"Gaps > {GAP_SECONDS} s:   {len(gaps)} within segments" + (f", lengths in s: {', '.join(f'{g:.0f}' for g in gaps)}" if gaps else ""))
    print(f"Largest gap:  {max(gaps, default=0):.0f} s")
    print(f"Pauses:       {', '.join(f'{p:.0f} s' for p in pauses) if pauses else 'none'} between segments")
    print(f"Distance:     {meters / 1000:.2f} km (straight lines within segments, jumps left out)")
    print(f"Jumps:        {jumps} above {JUMP_METERS_PER_SECOND} m/s")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
