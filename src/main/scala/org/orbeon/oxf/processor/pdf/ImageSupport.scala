/**
 * Copyright (C) 2020 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.processor.pdf

import java.awt.geom.AffineTransform
import java.awt.image.{AffineTransformOp, BufferedImage}
import java.io.{ByteArrayOutputStream, InputStream}

import javax.imageio.stream.MemoryCacheImageOutputStream
import javax.imageio.{IIOImage, ImageIO, ImageWriteParam}
import org.orbeon.oxf.util.CollectionUtils._
import org.orbeon.oxf.util.CoreUtils._
import org.orbeon.oxf.util.ImageMetadata

import scala.collection.JavaConverters._

object ImageSupport {

  def findImageOrientation(is: InputStream): Option[Int] =
    ImageMetadata.findKnownMetadata(is, ImageMetadata.MetadataType.Orientation) collect {
      case i: Int => i.intValue
    }

  def findTransformation(orientation: Int, width: Int, height: Int): Option[AffineTransform] =
    orientation match {
      case 2 =>
        // Horizontal flip
        Some(
          new AffineTransform            |!>
            (_.scale(-1.0, 1.0))         |!>
            (_.translate(-width, 0))
        )
      case 3 =>
        // 180 degree rotation
        Some(
          new AffineTransform            |!>
            (_.translate(width, height)) |!>
            (_.rotate(Math.PI))
        )
      case 4 =>
        // Vertical flip
        Some(
          new AffineTransform            |!>
            (_.scale(1.0, -1.0))         |!>
            (_.translate(0, -height))
        )
      case 5 =>
        Some(
          new AffineTransform            |!>
            (_.rotate(-Math.PI / 2))     |!>
            (_.scale(-1.0, 1.0))
        )
      case 6 =>
        // 90 degree rotation
        Some(
          new AffineTransform            |!>
            (_.translate(height, 0))     |!>
            (_.rotate(Math.PI / 2))
        )
      case 7 =>
        Some(
          new AffineTransform            |!>
            (_.scale(-1.0, 1.0))         |!>
            (_.translate(-height, 0))    |!>
            (_.translate(0, width))      |!>
            (_.rotate(3 * Math.PI / 2))
        )
      case 8 =>
        // 270 degree rotation
        Some(
          new AffineTransform            |!>
            (_.translate(0, width))      |!>
            (_.rotate(3 * Math.PI / 2))
        )
      case _ =>
        None
    }

  def transformImage(sourceImage: BufferedImage, transform: AffineTransform): BufferedImage = {

    // Use `TYPE_NEAREST_NEIGHBOR` because we use transformations that shouldn't need more, and
    // if we uwe instead `TYPE_BICUBIC`, we end up with a color model that requires transparency,
    // and then `ImageIO.write()` cannot encode the image.
    val op = new AffineTransformOp(transform, AffineTransformOp.TYPE_NEAREST_NEIGHBOR)

    val destinationImage =
      op.createCompatibleDestImage(
        sourceImage,
        if (sourceImage.getType == BufferedImage.TYPE_BYTE_GRAY) sourceImage.getColorModel else null
      )

    op.filter(sourceImage, destinationImage)
  }

  def compressJpegImage(image: BufferedImage, compressionLevel: Float): Array[Byte] =
    ImageIO.getImageWritersByFormatName("jpg").asScala.nextOption() match {
      case Some(jpgWriter) =>

        val os = new ByteArrayOutputStream

        val jpgWriteParam =
          jpgWriter.getDefaultWriteParam |!>
          (_.setCompressionMode(ImageWriteParam.MODE_EXPLICIT)) |!>
          (_.setCompressionQuality(compressionLevel))

        jpgWriter.setOutput(new MemoryCacheImageOutputStream(os))
        jpgWriter.write(
          null,
          new IIOImage(image, null, null),
          jpgWriteParam
        )
        jpgWriter.dispose()

        os.toByteArray
      case None =>
        throw new IllegalArgumentException("can't find encoder for image")
    }
}
