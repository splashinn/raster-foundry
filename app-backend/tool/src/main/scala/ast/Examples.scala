package com.azavea.rf.tool.ast

import java.util.UUID


object Examples {
  import MapAlgebraAST._

  val B1 = Source(
    UUID.nameUUIDFromBytes("LANDSAT_BAND_1".getBytes),
    Some(NodeMetadata(
      Some("Landsat Blue"),
      Some("Wavelength: 0.45-0.52 μm"), None, None, None
    ))
  )
  val B2 = Source(
    UUID.nameUUIDFromBytes("LANDSAT_BAND_2".getBytes),
    Some(NodeMetadata(
      Some("Landsat Green"),
      Some("Wavelength: 0.52-0.60 μm"), None, None, None
    ))
  )
  val B3 = Source(
    UUID.nameUUIDFromBytes("LANDSAT_BAND_3".getBytes),
    Some(NodeMetadata(
      Some("Landsat Red"),
      Some("Wavelength: 0.63-0.69 μm"), None, None, None
    ))
  )
  val B4 = Source(
    UUID.nameUUIDFromBytes("LANDSAT_BAND_4".getBytes),
    Some(NodeMetadata(
      Some("Landsat Near Infrared (NIR)"),
      Some("Wavelength: 0.76-0.90 μm"), None, None, None
    ))
  )
  val B5 = Source(
    UUID.nameUUIDFromBytes("LANDSAT_BAND_5".getBytes),
    Some(NodeMetadata(
      Some("Landsat Shortwave Infrared (SWIR) 1"),
      Some("Wavelength: 1.55-1.75 μm"), None, None, None
    ))
  )
  val B6 = Source(
    UUID.nameUUIDFromBytes("LANDSAT_BAND_6".getBytes),
    Some(NodeMetadata(
      Some("Landsat Thermal"),
      Some("Wavelength: 10.40-12.50 μm"), None, None, None
    ))
  )
  val B7 = Source(
    UUID.nameUUIDFromBytes("LANDSAT_BAND_7".getBytes),
    Some(NodeMetadata(
      Some("Landsat Shortwave Infrared (SWIR) 2"),
      Some("Wavelength: 2.08-2.35 μm"), None, None, None
    ))
  )

  val phycocyanin =
    Addition(
      List(
        Constant(UUID.nameUUIDFromBytes("47.7".getBytes), 47.7, None),
        Multiplication(
          List(
            Constant(UUID.nameUUIDFromBytes("-9.21".getBytes), -9.21, None),
            Division(
              List(B4, B2),
              UUID.nameUUIDFromBytes("div1".getBytes), None
            )
          ), UUID.nameUUIDFromBytes("mult1".getBytes), None
        ),
        Multiplication(
          List(
            Constant(UUID.nameUUIDFromBytes("29.7".getBytes), 29.7, None),
            Division(
              List(B5, B2),
              UUID.nameUUIDFromBytes("div2".getBytes), None
            )
          ), UUID.nameUUIDFromBytes("mult2".getBytes), None
        ),
        Multiplication(
          List(
            Constant(UUID.nameUUIDFromBytes("-118".getBytes), -118, None),
            Division(
              List(B5, B4),
              UUID.nameUUIDFromBytes("div3".getBytes), None
            )
          ), UUID.nameUUIDFromBytes("mult3".getBytes), None
        ),
        Multiplication(
          List(
            Constant(UUID.nameUUIDFromBytes("-6.81".getBytes), -6.81, None),
            Division(
              List(B6, B4),
              UUID.nameUUIDFromBytes("div4".getBytes), None
            )
          ), UUID.nameUUIDFromBytes("mult4".getBytes), None
        ),
        Multiplication(
          List(
            Constant(UUID.nameUUIDFromBytes("41.9".getBytes), 41.9, None),
            Division(
              List(B7, B4),
              UUID.nameUUIDFromBytes("div5".getBytes), None
            )
          ), UUID.nameUUIDFromBytes("mult5".getBytes), None
        ),
        Multiplication(
          List(
            Constant(UUID.nameUUIDFromBytes("-14.7".getBytes), -14.7, None),
            Division(
              List(B7, B5),
              UUID.nameUUIDFromBytes("div6".getBytes), None
            )
          ), UUID.nameUUIDFromBytes("mult6".getBytes), None
        )
      ),
      UUID.nameUUIDFromBytes("add".getBytes),
      None
    )

}
