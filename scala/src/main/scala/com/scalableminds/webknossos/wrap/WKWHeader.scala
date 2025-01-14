/*
 * Copyright (C) 2011-2017 scalableminds UG (haftungsbeschränkt) & Co. KG. <http://scm.io>
 */
package com.scalableminds.webknossos.wrap

import com.google.common.io.{LittleEndianDataInputStream => DataInputStream}
import com.scalableminds.webknossos.wrap.util.{BoxImplicits, ResourceBox}
import java.io._
import java.nio.{ByteBuffer, ByteOrder}

import net.liftweb.common.{Box, Failure, Full}
import net.liftweb.util.Helpers.tryo

object BlockType extends Enumeration(1) {
  val Raw, LZ4, LZ4HC = Value

  def isCompressed(blockType: BlockType.Value) = blockType == LZ4 || blockType == LZ4HC

  def isUncompressed(blockType: BlockType.Value) = blockType == Raw
}

object VoxelType extends Enumeration(1) {
  val UInt8, UInt16, UInt32, UInt64, Float, Double, Int8, Int16, Int32, Int64 = Value

  def bytesPerVoxel(voxelType: VoxelType.Value): Int = voxelType match {
    case UInt8 => 1
    case UInt16 => 2
    case UInt32 => 4
    case UInt64 => 8
    case Float => 4
    case Double => 8
    case Int8 =>  1
    case Int16 => 2
    case Int32 => 4
    case Int64 => 8
  }
}

case class WKWHeader(
                 version: Int,
                 numBlocksPerCubeDimension: Int,
                 numVoxelsPerBlockDimension: Int,
                 blockType: BlockType.Value,
                 voxelType: VoxelType.Value,
                 numBytesPerVoxel: Int,
                 jumpTable: Array[Long]
               ) {

  def dataOffset: Long = jumpTable.head

  def isCompressed: Boolean = BlockType.isCompressed(blockType)

  def numBlocksPerCube: Int = numBlocksPerCubeDimension * numBlocksPerCubeDimension * numBlocksPerCubeDimension

  def numBytesPerBlock: Int = numVoxelsPerBlockDimension * numVoxelsPerBlockDimension * numVoxelsPerBlockDimension * numBytesPerVoxel

  def expectedFileSize: Long = {
    if (isCompressed) {
      jumpTable.last
    } else {
      dataOffset + numBytesPerBlock.toLong * numBlocksPerCube.toLong
    }
  }

  def blockBoundaries(blockIndex: Int): Box[(Long, Int)] = {
    if (blockIndex < numBlocksPerCube) {
      if (isCompressed) {
        val offset = jumpTable(blockIndex)
        val length = (jumpTable(blockIndex + 1) - offset).toInt
        Full((offset, length))
      } else {
        Full((dataOffset + numBytesPerBlock.toLong * blockIndex.toLong, numBytesPerBlock))
      }
    } else {
      Failure("BlockIndex out of bounds")
    }
  }

  def blockLengths: Iterator[Int] = {
    if (isCompressed) {
      jumpTable.sliding(2).map(a => (a(1) - a(0)).toInt)
    } else {
      Iterator.fill(numBlocksPerCube)(numBytesPerBlock)
    }
  }

  def writeTo(output: DataOutput, isHeaderFile: Boolean = false): Unit = {
    output.write(WKWHeader.magicBytes)
    output.writeByte(WKWHeader.currentVersion)
    val numBlocksPerCubeDimensionLog2 = (math.log(numBlocksPerCubeDimension) / math.log(2)).toInt
    val numVoxelsPerBlockDimensionLog2 = (math.log(numVoxelsPerBlockDimension) / math.log(2)).toInt
    val sideLengths = (numBlocksPerCubeDimensionLog2 << 4) + numVoxelsPerBlockDimensionLog2
    output.writeByte(sideLengths)
    output.writeByte(blockType.id)
    output.writeByte(voxelType.id)
    output.writeByte(numBytesPerVoxel)
    if (isHeaderFile) {
      output.writeLong(0L)
    } else {
      val realDataOffset = 8L + jumpTable.length * 8L
      val jumpTableBuffer = ByteBuffer.allocate(jumpTable.length * 8)
      jumpTableBuffer.order(ByteOrder.LITTLE_ENDIAN)
      jumpTable.map(_ - dataOffset + realDataOffset).foreach(jumpTableBuffer.putLong)
      output.write(jumpTableBuffer.array)
    }
  }
}

object WKWHeader extends BoxImplicits {

  private def error(msg: String, expected: Any, actual: Any): String = {
    s"""Error reading WKW header: ${msg} [expected: ${expected}, actual: ${actual}]."""
  }

  private def error(msg: String): String = {
    s"""Error reading WKW header: ${msg}."""
  }

  val magicBytes = "WKW".getBytes
  val currentVersion = 1

  def apply(dataStream: DataInputStream, readJumpTable: Boolean): Box[WKWHeader] = {
    val magicByteBuffer: Array[Byte] = Array.ofDim[Byte](magicBytes.length)
    dataStream.read(magicByteBuffer, 0, magicBytes.length)
    val version = dataStream.readUnsignedByte()
    val sideLengths = dataStream.readUnsignedByte()
    val numBlocksPerCubeDimension = 1 << (sideLengths >>> 4) // fileSideLength [higher nibble]
    val numVoxelsPerBlockDimension = 1 << (sideLengths & 0x0f) // blockSideLength [lower nibble]
    val blockTypeId = dataStream.readUnsignedByte()
    val voxelTypeId = dataStream.readUnsignedByte()
    val numBytesPerVoxel = dataStream.readUnsignedByte() // voxel-size

    for {
      _ <- (magicByteBuffer.sameElements(magicBytes)) ?~! error("Invalid magic bytes", magicBytes, magicByteBuffer)
      _ <- (version == currentVersion) ?~! error("Unknown version", currentVersion, version)
      // We only support fileSideLengths < 1024, so that the total number of blocks per file fits in an Int.
      _ <- (numBlocksPerCubeDimension < 1024) ?~! error("Specified fileSideLength not supported", numBlocksPerCubeDimension, "[0, 1024)")
      // We only support blockSideLengths < 1024, so that the total number of voxels per block fits in an Int.
      _ <- (numBlocksPerCubeDimension < 1024) ?~! error("Specified blockSideLength not supported", numVoxelsPerBlockDimension, "[0, 1024)")
      blockType <- tryo(BlockType(blockTypeId)) ?~! error("Specified blockType is not supported")
      voxelType <- tryo(VoxelType(voxelTypeId)) ?~! error("Specified voxelType is not supported")
    } yield {
      val jumpTable = if (BlockType.isCompressed(blockType) && readJumpTable) {
        val numBlocksPerCube = numBlocksPerCubeDimension * numBlocksPerCubeDimension * numBlocksPerCubeDimension
        (0 to numBlocksPerCube).map(_ => dataStream.readLong()).toArray
      } else {
        Array(dataStream.readLong())
      }
      new WKWHeader(version, numBlocksPerCubeDimension, numVoxelsPerBlockDimension, blockType, voxelType, numBytesPerVoxel, jumpTable)
    }
  }

  def apply(file: File, readJumpTable: Boolean = false): Box[WKWHeader] = {
    ResourceBox.manage(new DataInputStream(new BufferedInputStream(new FileInputStream(file))))(apply(_, readJumpTable))
  }

  def apply(numBlocksPerCubeDimension: Int, numVoxelsPerBlockDimension: Int, blockType: BlockType.Value, voxelType: VoxelType.Value, numChannels: Int) = {
    new WKWHeader(
      currentVersion,
      numBlocksPerCubeDimension,
      numVoxelsPerBlockDimension,
      blockType,
      voxelType,
      VoxelType.bytesPerVoxel(voxelType) * numChannels,
      Array(0)
    )
  }
}
