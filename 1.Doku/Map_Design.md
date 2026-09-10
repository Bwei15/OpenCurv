{
  "version": 8,
  "name": "OpenCurv",
  "metadata": {
    "mapbox:autocomposite": false,
    "mapbox:type": "template",
    "maputnik:renderer": "mbgljs",
    "openmaptiles:version": "3.x",
    "openmaptiles:mapbox:owner": "openmaptiles",
    "openmaptiles:mapbox:source:url": "mapbox://openmaptiles.4qljc88t"
  },
  "sources": {
    "openmaptiles": {
      "type": "vector",
      "url": "https://api.maptiler.com/tiles/v3-openmaptiles/tiles.json?key=get_your_own_OpIi9ZULNHzrESv6T2vL"
    }
  },
  "sprite": "https://openmaptiles.github.io/maptiler-basic-gl-style/sprite",
  "glyphs": "https://api.maptiler.com/fonts/{fontstack}/{range}.pbf?key=get_your_own_OpIi9ZULNHzrESv6T2vL",
  "layers": [
    {
      "id": "background",
      "type": "background",
      "paint": {
        "background-color": "#F1EFE8"
      }
    },
    {
      "id": "landuse-residential",
      "type": "fill",
      "source": "openmaptiles",
      "source-layer": "landuse",
      "filter": [
        "all",
        ["==", "$type", "Polygon"],
        ["in", "class", "residential", "suburb", "neighbourhood"]
      ],
      "paint": {
        "fill-color": "#EDEAE2",
        "fill-opacity": 0.85
      }
    },
    {
      "id": "landcover_grass",
      "type": "fill",
      "source": "openmaptiles",
      "source-layer": "landcover",
      "filter": ["==", "class", "grass"],
      "paint": {
        "fill-color": "#DCF0D6",
        "fill-opacity": 0.95
      }
    },
    {
      "id": "landcover_wood",
      "type": "fill",
      "source": "openmaptiles",
      "source-layer": "landcover",
      "filter": ["==", "class", "wood"],
      "paint": {
        "fill-color": "#CCEAC3",
        "fill-opacity": 0.95
      }
    },
    {
      "id": "water",
      "type": "fill",
      "source": "openmaptiles",
      "source-layer": "water",
      "filter": [
        "all",
        ["==", "$type", "Polygon"],
        ["!=", "intermittent", 1]
      ],
      "paint": {
        "fill-color": "#AAD3DF"
      }
    },
    {
      "id": "waterway",
      "type": "line",
      "source": "openmaptiles",
      "source-layer": "waterway",
      "filter": ["==", "$type", "LineString"],
      "paint": {
        "line-color": "#AAD3DF",
        "line-width": {
          "base": 1.4,
          "stops": [[8, 1], [18, 4]]
        }
      }
    },
    {
      "id": "building",
      "type": "fill",
      "source": "openmaptiles",
      "source-layer": "building",
      "paint": {
        "fill-color": "#DFDDD5",
        "fill-opacity": {
          "base": 1,
          "stops": [[13, 0], [15, 0.65]]
        },
        "fill-outline-color": "#D3D0C6"
      }
    },
    {
      "id": "road_minor_casing",
      "type": "line",
      "source": "openmaptiles",
      "source-layer": "transportation",
      "minzoom": 12,
      "filter": [
        "all",
        ["==", "$type", "LineString"],
        ["in", "class", "minor", "service"]
      ],
      "layout": {
        "line-cap": "round",
        "line-join": "round"
      },
      "paint": {
        "line-color": "#DCD8CE",
        "line-width": {
          "base": 1.4,
          "stops": [[12, 1.8], [15, 4.5], [18, 14]]
        }
      }
    },
    {
      "id": "road_minor_core",
      "type": "line",
      "source": "openmaptiles",
      "source-layer": "transportation",
      "minzoom": 12,
      "filter": [
        "all",
        ["==", "$type", "LineString"],
        ["in", "class", "minor", "service"]
      ],
      "layout": {
        "line-cap": "round",
        "line-join": "round"
      },
      "paint": {
        "line-color": "#FFFFFF",
        "line-width": {
          "base": 1.4,
          "stops": [[12, 1], [15, 3.2], [18, 11]]
        }
      }
    },
    {
      "id": "road_major_casing",
      "type": "line",
      "source": "openmaptiles",
      "source-layer": "transportation",
      "filter": [
        "all",
        ["==", "$type", "LineString"],
        ["in", "class", "primary", "secondary", "tertiary", "trunk"]
      ],
      "layout": {
        "line-cap": "round",
        "line-join": "round"
      },
      "paint": {
        "line-color": "#E5C16C",
        "line-width": {
          "base": 1.4,
          "stops": [[6, 2], [11, 4.8], [14, 8], [18, 18]]
        }
      }
    },
    {
      "id": "road_major_core",
      "type": "line",
      "source": "openmaptiles",
      "source-layer": "transportation",
      "filter": [
        "all",
        ["==", "$type", "LineString"],
        ["in", "class", "primary", "secondary", "tertiary", "trunk"]
      ],
      "layout": {
        "line-cap": "round",
        "line-join": "round"
      },
      "paint": {
        "line-color": "#FFE082",
        "line-width": {
          "base": 1.4,
          "stops": [[6, 1.2], [11, 3.2], [14, 6], [18, 14]]
        }
      }
    },
    {
      "id": "road_label",
      "type": "symbol",
      "source": "openmaptiles",
      "source-layer": "transportation_name",
      "minzoom": 13,
      "filter": ["==", "$type", "LineString"],
      "layout": {
        "symbol-placement": "line",
        "text-field": "{name:latin}",
        "text-font": ["Noto Sans Regular"],
        "text-size": {
          "base": 1.2,
          "stops": [[12, 9], [17, 11]]
        }
      },
      "paint": {
        "text-color": "#70757A",
        "text-halo-color": "#FFFFFF",
        "text-halo-width": 2
      }
    },
    {
      "id": "poi_fuel_pin",
      "type": "circle",
      "source": "openmaptiles",
      "source-layer": "poi",
      "minzoom": 11,
      "filter": ["in", "class", "fuel", "petrol"],
      "paint": {
        "circle-radius": {
          "base": 1.4,
          "stops": [[11, 4.5], [14, 7], [17, 10]]
        },
        "circle-color": "#1A73E8",
        "circle-stroke-color": "#FFFFFF",
        "circle-stroke-width": 2
      }
    },
    {
      "id": "poi_fuel_label",
      "type": "symbol",
      "source": "openmaptiles",
      "source-layer": "poi",
      "minzoom": 12,
      "filter": ["in", "class", "fuel", "petrol"],
      "layout": {
        "text-anchor": "left",
        "text-field": "{name:latin}",
        "text-font": ["Noto Sans Bold"],
        "text-size": 11,
        "text-offset": [0.9, 0],
        "text-allow-overlap": false
      },
      "paint": {
        "text-color": "#185ABC",
        "text-halo-color": "#FFFFFF",
        "text-halo-width": 2.5
      }
    },
    {
      "id": "poi_food_pin",
      "type": "circle",
      "source": "openmaptiles",
      "source-layer": "poi",
      "minzoom": 13,
      "filter": ["in", "class", "restaurant", "cafe", "fast_food"],
      "paint": {
        "circle-radius": 5,
        "circle-color": "#EA4335",
        "circle-stroke-color": "#FFFFFF",
        "circle-stroke-width": 1.5
      }
    },
    {
      "id": "poi_food_label",
      "type": "symbol",
      "source": "openmaptiles",
      "source-layer": "poi",
      "minzoom": 14,
      "filter": ["in", "class", "restaurant", "cafe", "fast_food"],
      "layout": {
        "text-anchor": "left",
        "text-field": "{name:latin}",
        "text-font": ["Noto Sans Regular"],
        "text-size": 10,
        "text-offset": [0.8, 0]
      },
      "paint": {
        "text-color": "#C5221F",
        "text-halo-color": "#FFFFFF",
        "text-halo-width": 2
      }
    },
    {
      "id": "place_label_town",
      "type": "symbol",
      "source": "openmaptiles",
      "source-layer": "place",
      "minzoom": 9,
      "filter": [
        "all",
        ["==", "$type", "Point"],
        ["in", "class", "city", "town", "village", "suburb"]
      ],
      "layout": {
        "text-field": "{name:latin}",
        "text-font": ["Noto Sans Bold"],
        "text-size": {
          "stops": [[8, 11], [14, 13]]
        },
        "text-letter-spacing": 0.04,
        "text-transform": "uppercase"
      },
      "paint": {
        "text-color": "#3C4043",
        "text-halo-color": "#FFFFFF",
        "text-halo-width": 2.5
      }
    }
  ],
  "id": "opencurv
}