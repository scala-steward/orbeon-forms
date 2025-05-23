/**
 * Copyright (C) 2017 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.builder

import autowire.*
import enumeratum.EnumEntry.Hyphencase
import enumeratum.{Enum, EnumEntry}
import org.orbeon.builder.rpc.FormBuilderRpcApi
import org.orbeon.datatypes.{AboveBelow, Orientation}
import org.orbeon.jquery.Offset
import org.orbeon.oxf.util.CoreUtils.{asUnit, *}
import org.orbeon.xforms.$
import org.orbeon.xforms.rpc.RpcClient
import org.scalajs.dom.document
import io.udash.wrappers.jquery.JQuery
import org.orbeon.web.DomSupport.DomEventOps
import org.scalajs.dom

import scala.collection.immutable
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.*


object RowEditor {

  private var currentGridBodyOpt  : Option[Block] = None
  private var currentRowPosOpt    : Option[Int]   = None
  private def rowEditorContainer  : JQuery        = $(".fb-row-editor")

  private def withCurrentGridBody[T](f: Block => T): Option[T] =
    currentGridBodyOpt flatMap { currentGridBody =>
      // Check the current grid is still in the document, as we might have an outdated reference in case we read it in
      // `onUnderPointerChange`, since the current grid is also updated in another listener to `onUnderPointerChange`
      val gridIsInDocument =
        $.contains(document.documentElement, currentGridBody.el.get(0).get)
      gridIsInDocument option f(currentGridBody)
    }

  sealed trait RowEditor extends EnumEntry with Hyphencase {
    def className = s".fb-row-$entryName"
  }
  object RowEditor extends Enum[RowEditor] {
    val values: immutable.IndexedSeq[RowEditor] = findValues
    case object InsertAbove extends RowEditor
    case object Delete      extends RowEditor
    case object InsertBelow extends RowEditor
  }

  import RowEditor._

  Position.currentContainerChanged(
    containerCache = BlockCache.gridBodyCache,
    wasCurrent     = (_: Block) => (),
    becomesCurrent = (sectionGridBody: Block) => currentGridBodyOpt = Some(sectionGridBody)
  )

  // Position row editor
  Position.onUnderPointerChange(() => {
    withCurrentGridBody { currentGridBody =>

      case class TopBottom(top: Double, bottom: Double)

      // For each row track, find its top/bottom
      val rowsTopBottom = {
        val gridBodyTop = currentGridBody.top
        val zero = List(TopBottom(0, gridBodyTop))
        val rowsHeight = Position.tracksWidth(currentGridBody.el, Orientation.Horizontal)
        rowsHeight.foldLeft(zero) { (soFar: List[TopBottom], rowHeight: Double) =>
          val lastBottom = soFar.last.bottom
          val newTopBottom = TopBottom(lastBottom, lastBottom + rowHeight)
          soFar :+ newTopBottom
        }.drop(1)
      }

      // Find top/bottom of the row track the pointer is on
      val pointerRowTopBottomIndexOpt = {
        val pointerTop = Position.pointerPos.top + Position.scrollTop()
        rowsTopBottom.zipWithIndex.find { case (topBottom, _) =>
          topBottom.top <= pointerTop && pointerTop <= topBottom.bottom
        }
      }

      // Find where to position the row editor on the left
      val gridEl = currentGridBody.el.closest(BlockCache.GridSelector)
      val containerLeft = Offset(gridEl).left

      // Position row editor
      pointerRowTopBottomIndexOpt.foreach(pointerRowTopBottom => {

        val rowTop    = pointerRowTopBottom._1.top
        val rowBottom = pointerRowTopBottom._1.bottom
        val rowHeight = rowBottom - rowTop
        val rowIndex  = pointerRowTopBottom._2

        rowEditorContainer.show()
        Offset.offset(
          el     = rowEditorContainer,
          offset = Offset(
            left = containerLeft,
            top  = rowTop - Position.scrollTop()
          )
        )

        rowEditorContainer.children().hide()

        def positionElWithClass(selector: String, topOffset: JQuery => Double): Unit = {
          val elem = rowEditorContainer.children(selector)
          elem.show()
          Offset.offset(
            el     = elem,
            offset = Offset(
              left = containerLeft,
              top  = topOffset(elem) - Position.scrollTop()
            )
          )
        }

        currentRowPosOpt = Some(rowIndex + 1)
        positionElWithClass(RowEditor.InsertAbove.className, _ => rowTop)
        if (currentGridBody.el.closest(".fr-grid").is(".fb-can-delete-row"))
          positionElWithClass(RowEditor.Delete.className, e => rowTop + rowHeight/2 - e.height()/2)
        positionElWithClass(RowEditor.InsertBelow.className, e => rowBottom - e.height())
      })
    }
  })

  BlockCache.onExitFbMainOrOffsetMayHaveChanged { () =>
    rowEditorContainer.hide()
    currentGridBodyOpt = None
  }

  document.addEventListener("click", (clickEvent: dom.Event) => {
    RowEditor.values foreach { rowEditor =>
      val iconEl = rowEditorContainer.children(rowEditor.className)
      if (iconEl.is(clickEvent.targetT)) {
        withCurrentGridBody { currentGridBody =>
          currentRowPosOpt foreach { currentRowPos =>

            val gridEl    = currentGridBody.el.closest(BlockCache.GridSelector)
            val controlId = gridEl.attr("id").get
            val client    = RpcClient[FormBuilderRpcApi]

            rowEditor match {
              case InsertAbove => client.rowInsert(controlId, currentRowPos, AboveBelow.Above.entryName).call()
              case Delete      => client.rowDelete(controlId, currentRowPos).call()
              case InsertBelow => client.rowInsert(controlId, currentRowPos, AboveBelow.Below.entryName).call()
            }
          }
        }
      }
    }
  })

}
