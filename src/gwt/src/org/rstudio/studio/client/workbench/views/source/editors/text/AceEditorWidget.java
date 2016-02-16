/*
 * AceEditorWidget.java
 *
 * Copyright (C) 2009-12 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */
package org.rstudio.studio.client.workbench.views.source.editors.text;

import java.util.ArrayList;
import java.util.List;

import com.google.gwt.core.client.JsArray;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.core.client.Scheduler.RepeatingCommand;
import com.google.gwt.core.client.Scheduler.ScheduledCommand;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.dom.client.Element;
import com.google.gwt.event.dom.client.*;
import com.google.gwt.event.logical.shared.AttachEvent;
import com.google.gwt.event.logical.shared.HasValueChangeHandlers;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.event.shared.HandlerManager;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.event.shared.HasHandlers;
import com.google.gwt.user.client.Command;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.RequiresResize;
import com.google.inject.Inject;

import org.rstudio.core.client.BrowseCap;
import org.rstudio.core.client.CommandWithArg;
import org.rstudio.core.client.Debug;
import org.rstudio.core.client.StringUtil;
import org.rstudio.core.client.widget.FontSizer;
import org.rstudio.studio.client.RStudioGinjector;
import org.rstudio.studio.client.application.events.EventBus;
import org.rstudio.studio.client.common.debugging.model.Breakpoint;
import org.rstudio.studio.client.events.*;
import org.rstudio.studio.client.server.Void;
import org.rstudio.studio.client.workbench.commands.Commands;
import org.rstudio.studio.client.workbench.commands.RStudioCommandExecutedFromShortcutEvent;
import org.rstudio.studio.client.workbench.views.output.lint.LintResources;
import org.rstudio.studio.client.workbench.views.output.lint.model.AceAnnotation;
import org.rstudio.studio.client.workbench.views.output.lint.model.LintItem;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.AceClickEvent;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.AceDocumentChangeEventNative;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.AceEditorNative;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.AceMouseEventNative;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.Anchor;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.AnchoredRange;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.ExecuteChunksEvent;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.LineWidgetManager;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.Marker;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.Position;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.Range;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.events.AfterAceRenderEvent;
import org.rstudio.studio.client.workbench.views.source.editors.text.events.*;
import org.rstudio.studio.client.workbench.views.source.editors.text.events.FoldChangeEvent.Handler;
import org.rstudio.studio.client.workbench.views.source.editors.text.rmd.ChunkIconsManager;

public class AceEditorWidget extends Composite
      implements RequiresResize,
                 HasValueChangeHandlers<Void>,
                 HasFoldChangeHandlers,
                 HasAllKeyHandlers,
                 EditEvent.Handler
{
   
   public AceEditorWidget()
   {
      RStudioGinjector.INSTANCE.injectMembers(this);
      initWidget(new HTML());
      FontSizer.applyNormalFontSize(this);
      setSize("100%", "100%");

      capturingHandlers_ = new HandlerManager(this);
      addEventListener(getElement(), "keydown", capturingHandlers_);
      addEventListener(getElement(), "keyup", capturingHandlers_);
      addEventListener(getElement(), "keypress", capturingHandlers_);

      addStyleName("loading");

      editor_ = AceEditorNative.createEditor(getElement());
      editor_.manageDefaultKeybindings();
      editor_.getRenderer().setHScrollBarAlwaysVisible(false);
      editor_.getRenderer().setScrollPastEnd(true);
      editor_.setShowPrintMargin(false);
      editor_.setPrintMarginColumn(0);
      editor_.setHighlightActiveLine(false);
      editor_.setHighlightGutterLine(false);
      editor_.delegateEventsTo(AceEditorWidget.this);
      editor_.onChange(new CommandWithArg<AceDocumentChangeEventNative>()
      {
         public void execute(AceDocumentChangeEventNative event)
         {
            // Case 3815: It appears to be possible for change events to be
            // fired recursively, which exhausts the stack. This shouldn't 
            // happen, but since it has in at least one setting, guard against
            // recursion here.
            if (inOnChangeHandler_)
            {
               Debug.log("Warning: ignoring recursive ACE change event");
               return;
            }
            inOnChangeHandler_ = true;
            try
            {
               ValueChangeEvent.fire(AceEditorWidget.this, null);
               AceEditorWidget.this.fireEvent(new DocumentChangedEvent(event));
               
               updateBreakpoints(event);
               updateAnnotations(event);
               
               // Immediately re-render on change if we have markers, to
               // ensure they're re-drawn in the correct locations.
               if (editor_.getSession().getMarkers(true).size() > 0)
               {
                  Scheduler.get().scheduleDeferred(new ScheduledCommand()
                  {
                     @Override
                     public void execute()
                     {
                        editor_.getRenderer().renderMarkers();
                     }
                  });
               }
            }
            catch (Exception ex)
            {
               Debug.log("Exception occurred during ACE change event: " + 
                         ex.getMessage());
            }
            inOnChangeHandler_ = false;
         }

      });
      editor_.onChangeFold(new Command()
      {
         @Override
         public void execute()
         {
            fireEvent(new FoldChangeEvent());
         }
      });
      editor_.onGutterMouseDown(new CommandWithArg<AceMouseEventNative>()
      {
        @Override
        public void execute(AceMouseEventNative arg)
        {
           // make sure the click is actually intended for the gutter
           com.google.gwt.dom.client.Element targetElement = 
                 Element.as(arg.getNativeEvent().getEventTarget());
           if (targetElement.getClassName().indexOf("ace_gutter-cell") < 0)
           {
              return;
           }
           
           NativeEvent evt = arg.getNativeEvent();
           
           // right-clicking shouldn't set a breakpoint
           if (evt.getButton() != NativeEvent.BUTTON_LEFT) 
           {
              return;
           }
           
           // make sure that the click was in the left half of the element--
           // clicking on the line number itself (or the gutter near the 
           // text) shouldn't set a breakpoint.
           if (evt.getClientX() < 
               (targetElement.getAbsoluteLeft() + 
                     (targetElement.getClientWidth() / 2))) 
           {
              toggleBreakpointAtPosition(arg.getDocumentPosition());            
           }
        }
      });
      editor_.getSession().getSelection().addCursorChangeHandler(new CommandWithArg<Position>()
      {
         public void execute(Position arg)
         {
            AceEditorWidget.this.fireEvent(new CursorChangedEvent(arg));
         }
      });
      
      aceEventHandlers_ = new ArrayList<HandlerRegistration>();
      
      aceEventHandlers_.add(AceEditorNative.addEventListener(
                  editor_,
                  "undo",
                  new CommandWithArg<Void>()
                  {
                     public void execute(Void arg)
                     {
                        fireEvent(new UndoRedoEvent(false));
                     }
                  }));
      
      aceEventHandlers_.add(AceEditorNative.addEventListener(
                  editor_,
                  "redo",
                  new CommandWithArg<Void>()
                  {
                     public void execute(Void arg)
                     {
                        fireEvent(new UndoRedoEvent(true));
                     }
                  }));
      
      aceEventHandlers_.add(AceEditorNative.addEventListener(
                  editor_,
                  "paste",
                  new CommandWithArg<String>()
                  {
                     public void execute(String text)
                     {
                        fireEvent(new PasteEvent(text));
                     }
                  }));
      
      aceEventHandlers_.add(AceEditorNative.addEventListener(
                  editor_,
                  "mousedown",
                  new CommandWithArg<AceMouseEventNative>()
                  {
                     @Override
                     public void execute(AceMouseEventNative event)
                     {
                        fireEvent(new AceClickEvent(event));
                     }
                  }));
      
      aceEventHandlers_.add(AceEditorNative.addEventListener(
            editor_.getRenderer(),
            "afterRender",
            new CommandWithArg<Void>()
            {
               @Override
               public void execute(Void event)
               {
                  fireEvent(new RenderFinishedEvent());
                  isRendered_ = true;
                  events_.fireEvent(new AfterAceRenderEvent(AceEditorWidget.this.getEditor()));
               }
            }));
      
      addAttachHandler(new AttachEvent.Handler()
      {
         @Override
         public void onAttachOrDetach(AttachEvent event)
         {
            if (!event.isAttached())
            {
               for (HandlerRegistration registration : aceEventHandlers_)
                  registration.removeHandler();
               aceEventHandlers_.clear();
            }
         }
      });
      
      if (!hasEditHandlers_)
      {
         events_.addHandler(EditEvent.TYPE, this);
         hasEditHandlers_ = true;
      }
      
      events_.addHandler(
            RStudioCommandExecutedFromShortcutEvent.TYPE,
            new RStudioCommandExecutedFromShortcutEvent.Handler()
            {
               @Override
               public void onRStudioCommandExecutedFromShortcut(RStudioCommandExecutedFromShortcutEvent event)
               {
                  clearKeyBuffers(editor_);
               }
            });
   }
   
   // When the 'keyBinding' field is initialized (the field holding all keyboard
   // handlers for an Ace editor), an associated '$data' element is used to store
   // information on keys (to allow for keyboard chaining, and so on). We refresh
   // that data whenever an RStudio AppCommand is executed (thereby ensuring that
   // the current keybuffer is cleared as far as Ace is concerned)
   private static final native void clearKeyBuffers(AceEditorNative editor) /*-{
      var keyBinding = editor.keyBinding;
      keyBinding.$data = {editor: editor};
   }-*/;
   
   public void onEdit(EditEvent edit)
   {
      if (edit.isBeforeEdit())
         maybeUnmap(edit.getType());
      else
         maybeRemap(edit.getType());
   }
   
   private final void maybeUnmap(int type)
   {
      if (type == EditEvent.TYPE_COPY)
         maybeUnmapImpl("<C-c>", "c-c");
      else if (type == EditEvent.TYPE_CUT)
         maybeUnmapImpl("<C-x>", "c-x");
      else if (type == EditEvent.TYPE_PASTE)
         maybeUnmapImpl("<C-v>", "c-v");
   }
   
   private final void maybeRemap(int type)
   {
      if (type == EditEvent.TYPE_COPY)
         maybeRemapImpl("<C-c>", "c-c");
      else if (type == EditEvent.TYPE_CUT)
         maybeRemapImpl("<C-x>", "c-x");
      else if (type == EditEvent.TYPE_PASTE)
         maybeRemapImpl("<C-v>", "c-v");
   }
   
   private static final native void maybeUnmapImpl(String vimKeys, String emacsKeys)
   /*-{
      
      // Handle Vim mapping
      var Vim = $wnd.require("ace/keyboard/vim").handler;
      var keymap = Vim.defaultKeymap;
      for (var i = 0; i < keymap.length; i++) {
         if (keymap[i].keys === vimKeys) {
            keymap[i].keys = "DISABLED:" + vimKeys;
            break;
         }
      }
      
      // Handle Emacs mapping
      var Emacs = $wnd.require("ace/keyboard/emacs").handler;
      var bindings = Emacs.commandKeyBinding;
      bindings["DISABLED:" + emacsKeys] = bindings[emacsKeys];
      delete bindings[emacsKeys];
      
      
   }-*/;
   
   private static final native void maybeRemapImpl(String vimKeys, String emacsKeys)
   /*-{
      
      // Handle Vim mapping
      var Vim = $wnd.require("ace/keyboard/vim").handler;
      var keymap = Vim.defaultKeymap;
      for (var i = 0; i < keymap.length; i++) {
         if (keymap[i].keys === "DISABLED:" + vimKeys) {
            keymap[i].keys = vimKeys;
            break;
         }
      }
      
      // Handle Emacs mapping
      var Emacs = $wnd.require("ace/keyboard/emacs").handler;
      var bindings = Emacs.commandKeyBinding;
      bindings[emacsKeys] = bindings["DISABLED:" + emacsKeys];
      delete bindings["DISABLED:" + emacsKeys];
   }-*/;
   
   @Inject
   private void initialize(EventBus events, ChunkIconsManager manager)
   {
      events_ = events;
      chunkIconsManager_ = manager;
   }
   
   public HandlerRegistration addCursorChangedHandler(
         CursorChangedHandler handler)
   {
      return addHandler(handler, CursorChangedEvent.TYPE);
   }

   @Override
   public HandlerRegistration addFoldChangeHandler(Handler handler)
   {
      return addHandler(handler, FoldChangeEvent.TYPE);
   }
   
   public HandlerRegistration addBreakpointSetHandler
      (BreakpointSetEvent.Handler handler)
   {
      return addHandler(handler, BreakpointSetEvent.TYPE);
   }
   
   public HandlerRegistration addBreakpointMoveHandler
      (BreakpointMoveEvent.Handler handler)
   {
      return addHandler(handler, BreakpointMoveEvent.TYPE);
   }
   
   public void toggleBreakpointAtCursor()
   {
      Position pos = editor_.getSession().getSelection().getCursor();
      toggleBreakpointAtPosition(Position.create(pos.getRow(), 0));
   }
   
   public AceEditorNative getEditor() {
      return editor_;
   }

   @Override
   protected void onLoad()
   {
      super.onLoad();

      editor_.getRenderer().updateFontSize();
      onResize();
      
      fireEvent(new EditorLoadedEvent(editor_));
      events_.fireEvent(new EditorLoadedEvent(editor_));

      int delayMs = initToEmptyString_ ? 100 : 500;

      // On Windows desktop sometimes we inexplicably end up at the wrong size
      // if the editor is being resized while it's loading (such as when a new
      // document is created while the source pane is hidden)
      Scheduler.get().scheduleFixedDelay(new RepeatingCommand()
      {
         public boolean execute()
         {
            if (isAttached())
               onResize();
            removeStyleName("loading");
            return false;
         }
      }, delayMs);
   }

   public void onResize()
   {
      editor_.resize();
   }

   public void onActivate()
   {
      if (editor_ != null)
      {
         if (BrowseCap.INSTANCE.aceVerticalScrollBarIssue())
            editor_.getRenderer().forceScrollbarUpdate();
         editor_.getRenderer().updateFontSize();
         editor_.getRenderer().forceImmediateRender();
      }
   }

   public void setCode(String code)
   {
      code = StringUtil.notNull(code);
      initToEmptyString_ = code.length() == 0;
      editor_.getSession().setValue(code);
   }

   public HandlerRegistration addValueChangeHandler(ValueChangeHandler<Void> handler)
   {
      return addHandler(handler, ValueChangeEvent.getType());
   }

   public HandlerRegistration addFocusHandler(FocusHandler handler)
   {
      return addHandler(handler, FocusEvent.getType());
   }

   public HandlerRegistration addBlurHandler(BlurHandler handler)
   {
      return addHandler(handler, BlurEvent.getType());
   }

   public HandlerRegistration addClickHandler(ClickHandler handler)
   {
      return addDomHandler(handler, ClickEvent.getType());
   }

   public HandlerRegistration addEditorLoadedHandler(EditorLoadedHandler handler)
   {
      return addHandler(handler, EditorLoadedEvent.TYPE);
   }

   public HandlerRegistration addKeyDownHandler(KeyDownHandler handler)
   {
      return addHandler(handler, KeyDownEvent.getType());
   }

   public HandlerRegistration addKeyPressHandler(KeyPressHandler handler)
   {
      return addHandler(handler, KeyPressEvent.getType());
   }
   
   public HandlerRegistration addKeyUpHandler(KeyUpHandler handler)
   {
      return addHandler(handler, KeyUpEvent.getType());
   }

   public HandlerRegistration addCapturingKeyDownHandler(KeyDownHandler handler)
   {
      return capturingHandlers_.addHandler(KeyDownEvent.getType(), handler);
   }

   public HandlerRegistration addCapturingKeyPressHandler(KeyPressHandler handler)
   {
      return capturingHandlers_.addHandler(KeyPressEvent.getType(), handler);
   }

   public HandlerRegistration addCapturingKeyUpHandler(KeyUpHandler handler)
   {
      return capturingHandlers_.addHandler(KeyUpEvent.getType(), handler);
   }

   private static native void addEventListener(Element element,
                                        String event,
                                        HasHandlers handlers) /*-{
      var listener = $entry(function(e) {
         @com.google.gwt.event.dom.client.DomEvent::fireNativeEvent(Lcom/google/gwt/dom/client/NativeEvent;Lcom/google/gwt/event/shared/HasHandlers;Lcom/google/gwt/dom/client/Element;)(e, handlers, element);
      });
      element.addEventListener(event, listener, true);

   }-*/;

   public HandlerRegistration addUndoRedoHandler(UndoRedoHandler handler)
   {
      return addHandler(handler, UndoRedoEvent.TYPE);
   }

   public HandlerRegistration addPasteHandler(PasteEvent.Handler handler)
   {
      return addHandler(handler, PasteEvent.TYPE);
   }

   public HandlerRegistration addAceClickHandler(AceClickEvent.Handler handler)
   {
      return addHandler(handler, AceClickEvent.TYPE);
   }
   
   public HandlerRegistration addExecuteChunkHandler(ExecuteChunksEvent.Handler handler)
   {
      return addHandler(handler, ExecuteChunksEvent.TYPE);
   }
   
   public void forceResize()
   {
      editor_.getRenderer().onResize(true);
   }

   public void autoHeight()
   {
      editor_.autoHeight();
   }

   public void forceCursorChange()
   {
      editor_.onCursorChange();
   }
   
   public void addOrUpdateBreakpoint(Breakpoint breakpoint)
   {
      int idx = getBreakpointIdxById(breakpoint.getBreakpointId());
      if (idx >= 0)
      {
         removeBreakpointMarker(breakpoint);
         breakpoint.setEditorState(breakpoint.getState());
         breakpoint.setEditorLineNumber(breakpoint.getLineNumber());
      }
      else
      {
         breakpoints_.add(breakpoint);
      }
      placeBreakpointMarker(breakpoint);
   }
   
   public void removeBreakpoint(Breakpoint breakpoint)
   {
      int idx = getBreakpointIdxById(breakpoint.getBreakpointId());
      if (idx >= 0)
      {
         removeBreakpointMarker(breakpoint);
         breakpoints_.remove(idx);
      }
   }
   
   public void removeAllBreakpoints()
   {
      for (Breakpoint breakpoint: breakpoints_)
      {
         removeBreakpointMarker(breakpoint);
      }
      breakpoints_.clear();
   }
   
   public boolean hasBreakpoints()
   {
      return breakpoints_.size() > 0;
   }
   
   private void updateBreakpoints(AceDocumentChangeEventNative changeEvent)
   {
      // if there are no breakpoints, don't do any work to move them about
      if (breakpoints_.size() == 0)
      {
         return;
      }
      
      // see if we need to move any breakpoints around in response to 
      // this change to the document's text
      String action = changeEvent.getAction();
      Range range = changeEvent.getRange();
      Position start = range.getStart();
      Position end = range.getEnd();
      
      // if the edit was all on one line or the action didn't change text
      // in a way that could change lines, we can't have moved anything
      if (start.getRow() == end.getRow() || 
          (!action.equals("insertText") &&
           !action.equals("insertLines") &&
           !action.equals("removeText") &&
           !action.equals("removeLines")))
      {
         return;
      }
      
      int shiftedBy = 0;
      int shiftStartRow = 0;
      
      // compute how many rows to shift
      if (action == "insertText" || 
          action == "insertLines")
      {
         shiftedBy = end.getRow() - start.getRow();
      } 
      else
      {
         shiftedBy = start.getRow() - end.getRow();
      }
      
      // compute where to start shifting
      shiftStartRow = start.getRow() + 
            ((action == "insertText" && start.getColumn() > 0) ? 
                  1 : 0);
      
      // make a pass through the breakpoints and move them as appropriate:
      // remove all the breakpoints after the row where the change
      // happened, and add them back at their new position if they were
      // not part of a deleted range. 
      ArrayList<Breakpoint> movedBreakpoints = new ArrayList<Breakpoint>();
     
      for (int idx = 0; idx < breakpoints_.size(); idx++)
      {
         Breakpoint breakpoint = breakpoints_.get(idx);
         int breakpointRow = rowFromLine(breakpoint.getEditorLineNumber());
         if (breakpointRow >= shiftStartRow)
         {
            // remove the breakpoint from its old position
            movedBreakpoints.add(breakpoint);
            removeBreakpointMarker(breakpoint);
         }
      }
      for (Breakpoint breakpoint: movedBreakpoints)
      {
         // calculate the new position of the breakpoint
         int oldBreakpointPosition = 
               rowFromLine(breakpoint.getEditorLineNumber());
         int newBreakpointPosition = 
               oldBreakpointPosition + shiftedBy;
         
         // add a breakpoint in this new position only if it wasn't 
         // in a deleted range, and if we don't already have a
         // breakpoint there
         if (oldBreakpointPosition >= end.getRow() &&
             !(oldBreakpointPosition == end.getRow() && shiftedBy < 0) &&
             getBreakpointIdxByLine(lineFromRow(newBreakpointPosition)) < 0)
         {
            breakpoint.moveToLineNumber(lineFromRow(newBreakpointPosition));
            placeBreakpointMarker(breakpoint);
            fireEvent(new BreakpointMoveEvent(breakpoint.getBreakpointId())); 
         }
         else
         {
            breakpoints_.remove(breakpoint);
            fireEvent(new BreakpointSetEvent(
                  breakpoint.getEditorLineNumber(), 
                  breakpoint.getBreakpointId(),
                  false)); 
         }
      }
   }
   
   private void placeBreakpointMarker(Breakpoint breakpoint)
   {
      int line = breakpoint.getEditorLineNumber();
      if (breakpoint.getEditorState() == Breakpoint.STATE_ACTIVE)
      {
         editor_.getSession().setBreakpoint(rowFromLine(line));
      }
      else if (breakpoint.getEditorState() == Breakpoint.STATE_PROCESSING)
      {
        editor_.getRenderer().addGutterDecoration(
               rowFromLine(line), 
               "ace_pending-breakpoint");
      } 
      else if (breakpoint.getEditorState() == Breakpoint.STATE_INACTIVE)
      {
         editor_.getRenderer().addGutterDecoration(
               rowFromLine(line), 
               "ace_inactive-breakpoint");
      }
   }
   
   private void removeBreakpointMarker(Breakpoint breakpoint)
   {
      int line = breakpoint.getEditorLineNumber();
      if (breakpoint.getEditorState() == Breakpoint.STATE_ACTIVE)
      {
         editor_.getSession().clearBreakpoint(rowFromLine(line));
      }
      else if (breakpoint.getEditorState() == Breakpoint.STATE_PROCESSING)
      {
        editor_.getRenderer().removeGutterDecoration(
               rowFromLine(line), 
               "ace_pending-breakpoint");
      } 
      else if (breakpoint.getEditorState() == Breakpoint.STATE_INACTIVE)
      {
         editor_.getRenderer().removeGutterDecoration(
               rowFromLine(line), 
               "ace_inactive-breakpoint");
      }
   }
   
   private void toggleBreakpointAtPosition(Position pos)
   {
      // rows are 0-based, but debug line numbers are 1-based
      int lineNumber = lineFromRow(pos.getRow());
      int breakpointIdx = getBreakpointIdxByLine(lineNumber);

      // if there's already a breakpoint on that line, remove it
      if (breakpointIdx >= 0)
      {
         Breakpoint breakpoint = breakpoints_.get(breakpointIdx);
         removeBreakpointMarker(breakpoint);
         fireEvent(new BreakpointSetEvent(
               lineNumber, 
               breakpoint.getBreakpointId(),
               false));
         breakpoints_.remove(breakpointIdx);
      }

      // if there's no breakpoint on that line yet, create a new unset
      // breakpoint there (the breakpoint manager will pick up the new
      // breakpoint and attempt to set it on the server)
      else
      {
         try
         {
            // move the breakpoint down to the first line that has a
            // non-whitespace, non-comment token
            if (editor_.getSession().getMode().getCodeModel() != null)
            {
               Position tokenPos = editor_.getSession().getMode().getCodeModel()
                  .findNextSignificantToken(pos);
               if (tokenPos != null)
               {
                  lineNumber = lineFromRow(tokenPos.getRow());
                  if (getBreakpointIdxByLine(lineNumber) >= 0)
                  {
                     return;
                  }
               }
               else
               {
                  // if there are no tokens anywhere after the line, don't
                  // set a breakpoint
                  return;
               }
            }
         }
         catch (Exception e)
         {
            // If we failed at any point to fast-forward to the next line with
            // a statement, we'll try to set a breakpoint on the line the user
            // originally clicked. 
         }

         fireEvent(new BreakpointSetEvent(
               lineNumber,
               BreakpointSetEvent.UNSET_BREAKPOINT_ID,
               true));
      }
   }
   
   private int getBreakpointIdxById(int breakpointId)
   {
	   for (int idx = 0; idx < breakpoints_.size(); idx++)
	   {
	      if (breakpoints_.get(idx).getBreakpointId() == breakpointId)
	      {
	         return idx;
	      }
	   }
	   return -1;
   }
   
   private int getBreakpointIdxByLine(int lineNumber)
   {
      for (int idx = 0; idx < breakpoints_.size(); idx++)
      {
         if (breakpoints_.get(idx).getEditorLineNumber() == lineNumber)
         {
            return idx;
         }
      }
      return -1;
   }
   
   private int lineFromRow(int row)
   {
      return row + 1; 
   }
   
   private int rowFromLine(int line)
   {
      return line - 1;
   }
   
   // ---- Annotation related methods
   
   private AnchoredRange createAnchoredRange(Position start,
                                             Position end)
   {
      return getEditor().getSession().createAnchoredRange(start, end);
   }
   
   // This class binds an ace annotation (used for the gutter) with an
   // inline marker (the underlining for associated lint). We also store
   // the associated marker. Ie, with some beautiful ASCII art:
   //
   //
   //   1. | 
   //   2. | foo <- function(apple) {    
   //  /!\ |   print(Apple)
   //   3. | }       ~~~~~
   //   4. |
   //
   // The 'anchor' is associated with the position of the warning icon
   // /!\; while the anchored range is associated with the underlying
   // '~~~~~'. The marker id is needed to detach the annotation later.
   private class AnchoredAceAnnotation
   {
      public AnchoredAceAnnotation(AceAnnotation annotation,
                                   AnchoredRange range,
                                   int markerId)
      {
         annotation_ = annotation;
         range_ = range;
         anchor_ = Anchor.createAnchor(
               editor_.getSession().getDocument(),
               annotation.row(),
               annotation.column());
         markerId_ = markerId;
      }
      
      public int getMarkerId() { return markerId_; }
      
      public void detach()
      {
         if (range_ != null)
            range_.detach();
         
         if (anchor_ != null)
            anchor_.detach();
         
         editor_.getSession().removeMarker(markerId_);
      }
      
      public AceAnnotation asAceAnnotation()
      {
         return AceAnnotation.create(
               anchor_.getRow(),
               anchor_.getColumn(),
               annotation_.text(),
               annotation_.type());
      }
      
      private final AceAnnotation annotation_;
      private final AnchoredRange range_;
      private final Anchor anchor_;
      private final int markerId_;
   }

   public JsArray<AceAnnotation> getAnnotations()
   {
      JsArray<AceAnnotation> annotations =
            JsArray.createArray().cast();
      annotations.setLength(annotations_.size());
      
      for (int i = 0; i < annotations_.size(); i++)
         annotations.set(i, annotations_.get(i).asAceAnnotation());
      
      return annotations;
   }
   
   public void setAnnotations(JsArray<AceAnnotation> annotations)
   {
      clearAnnotations();
      editor_.getSession().setAnnotations(annotations);
   }
   
   public void showLint(JsArray<LintItem> lint)
   {
      clearAnnotations();
      JsArray<AceAnnotation> annotations = LintItem.asAceAnnotations(lint);
      editor_.getSession().setAnnotations(annotations);
      
      // Now, set (and cache) inline markers.
      for (int i = 0; i < lint.length(); i++)
      {
         LintItem item = lint.get(i);
         AnchoredRange range = createAnchoredRange(
               Position.create(item.getStartRow(), item.getStartColumn()),
               Position.create(item.getEndRow(), item.getEndColumn()));
         
         String clazz = "unknown";
         if (item.getType() == "error")
            clazz = lintStyles_.error();
         else if (item.getType() == "warning")
            clazz = lintStyles_.warning();
         else if (item.getType() == "info")
            clazz = lintStyles_.info();
         else if (item.getType() == "style")
            clazz = lintStyles_.style();
         
         int id = editor_.getSession().addMarker(range, clazz, "text", true);
         
         annotations_.add(new AnchoredAceAnnotation(
               annotations.get(i),
               range,
               id));
      }
   }
   
   public void clearLint()
   {
      clearAnnotations();
      editor_.getSession().setAnnotations(null);
   }
   
   private void updateAnnotations(AceDocumentChangeEventNative event)
   {
      Range range = event.getRange();
      
      ArrayList<AnchoredAceAnnotation> annotations =
            new ArrayList<AnchoredAceAnnotation>();

      for (int i = 0; i < annotations_.size(); i++)
      {
         AnchoredAceAnnotation annotation = annotations_.get(i);
         Position pos = annotation.anchor_.getPosition();
         
         if (!range.contains(pos))
            annotations.add(annotation);
         else
            annotation.detach();
      }
      annotations_ = annotations;
   }
   
   public void clearAnnotations()
   {
      for (int i = 0; i < annotations_.size(); i++)
         annotations_.get(i).detach();
      annotations_.clear();
   }
   
   public void removeMarkersOnCursorLine()
   {
      // Defer this so other event handling can update anchors etc.
      Scheduler.get().scheduleDeferred(new ScheduledCommand()
      {
         
         @Override
         public void execute()
         {
            int cursorRow = editor_.getCursorPosition().getRow();
            JsArray<AceAnnotation> newAnnotations = JsArray.createArray().cast();
            
            for (int i = 0; i < annotations_.size(); i++)
            {
               AnchoredAceAnnotation annotation = annotations_.get(i);
               int markerId = annotation.getMarkerId();
               Marker marker = editor_.getSession().getMarker(markerId);
               
               // The marker may have already been removed in response to
               // a previous action.
               if (marker == null)
                  continue;
               
               Range range = marker.getRange();
               int rowStart = range.getStart().getRow();
               int rowEnd = range.getEnd().getRow();
               
               if (cursorRow >= rowStart && cursorRow <= rowEnd)
                  editor_.getSession().removeMarker(markerId);
               else
                  newAnnotations.push(annotation.asAceAnnotation());
            }
            
            editor_.getSession().setAnnotations(newAnnotations);
            editor_.getRenderer().renderMarkers();
            
         }
      });
   }
   
   public void removeMarkersAtCursorPosition()
   {
      // Defer this so other event handling can update anchors etc.
      Scheduler.get().scheduleDeferred(new ScheduledCommand()
      {
         
         @Override
         public void execute()
         {
            Position cursor = editor_.getCursorPosition();
            JsArray<AceAnnotation> newAnnotations = JsArray.createArray().cast();
            
            for (int i = 0; i < annotations_.size(); i++)
            {
               AnchoredAceAnnotation annotation = annotations_.get(i);
               int markerId = annotation.getMarkerId();
               Marker marker = editor_.getSession().getMarker(markerId);
               
               // The marker may have already been removed in response to
               // a previous action.
               if (marker == null)
                  continue;
               
               Range range = marker.getRange();
               if (!range.contains(cursor))
                  newAnnotations.push(annotation.asAceAnnotation());
               else
                  editor_.getSession().removeMarker(markerId);
            }
            
            editor_.getSession().setAnnotations(newAnnotations);
            editor_.getRenderer().renderMarkers();
            
         }
      });
   }
   
   public void setDragEnabled(boolean enabled)
   {
      // the ACE API currently provides no way to disable dropping text 
      // from external sources specifically (the dragEnabled option affects
      // only internal ACE dragging); for now, just put the whole editor into
      // read-only mode while dragging, which prevents it from accepting the
      // text 
      editor_.setReadOnly(!enabled);
   }
   
   public LineWidgetManager getLineWidgetManager()
   {
      return editor_.getLineWidgetManager();
   }
   
   public boolean isRendered()
   {
      return isRendered_;
   }

   private final AceEditorNative editor_;
   private final HandlerManager capturingHandlers_;
   private final List<HandlerRegistration> aceEventHandlers_;
   private boolean initToEmptyString_ = true;
   private boolean inOnChangeHandler_ = false;
   private boolean isRendered_ = false;
   private ArrayList<Breakpoint> breakpoints_ = new ArrayList<Breakpoint>();
   
   private ArrayList<AnchoredAceAnnotation> annotations_ =
         new ArrayList<AnchoredAceAnnotation>();
   private LintResources.Styles lintStyles_ = LintResources.INSTANCE.styles();
   
   private EventBus events_;
   private ChunkIconsManager chunkIconsManager_;
   private Commands commands_ = RStudioGinjector.INSTANCE.getCommands();
   
   private static boolean hasEditHandlers_ = false;
}
