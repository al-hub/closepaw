package ai.closepaw.chatgpt.control

import org.junit.Assert.*
import org.junit.Test

class VisualKeyboardPolicyTest {
    private val keyboard = ControlKeyboard(2, "ime", "ime/.Keyboard")
    private val field = ControlNode(1, text="A", editable=true, focused=true, focusable=true,
        selectionStart=1, selectionEnd=1, sourceWindowId=1, sourcePackage="app")
    private val key = ControlNode(2, text="a", bounds="10 110 30 140", sourceWindowId=2,
        sourcePackage="ime", visualKeyCandidate=true, keyboardKey="a")
    private val frame = ControlFrame("app",1,"fingerprint",listOf(field,key),touchContext="geometry",
        keyboard=keyboard,inputTargetId=1,inputTarget=field)

    @Test fun imageAndMatchingNativeLabelAreBothRequired() {
        assertNull(VisualKeyboardPolicy.refusal(frame,key,"keyboard"))
        assertEquals("keyboard_visual_required",VisualKeyboardPolicy.refusal(frame,key,"app"))
        assertNotNull(VisualKeyboardPolicy.refusal(frame,key.copy(text="Enter"),"keyboard"))
        assertNotNull(VisualKeyboardPolicy.refusal(frame,key.copy(sourcePackage="other"),"keyboard"))
        // Original semantic admission is unchanged, despite the visual candidate.
        assertNull(KeyboardKeyPolicy.identify("a","","android.widget.TextView",false,false))
    }
    @Test fun permissionsFocusAndExactCaretRemainRequired() {
        assertNotNull(VisualKeyboardPolicy.refusal(frame.copy(keyboard=keyboard.copy(interactAllowed=false)),key,"keyboard"))
        assertNotNull(VisualKeyboardPolicy.refusal(frame.copy(inputTarget=field.copy(focused=false)),key,"keyboard"))
        assertNotNull(VisualKeyboardPolicy.refusal(frame.copy(inputTarget=field.copy(selectionStart=-1)),key,"keyboard"))
        assertNotNull(VisualKeyboardPolicy.refusal(frame,key.copy(password=true),"keyboard"))
    }
    @Test fun visualRouteNeverAdmitsSubmissionOrLayoutControls() {
        for (label in listOf("Enter","Send","Search","↵","Shift","한/영","suggestion","😀"))
            assertNull(VisualKeyboardPolicy.literal(label,""))
        assertNotNull(VisualKeyboardPolicy.literal("ㄱ",""))
        assertNotNull(VisualKeyboardPolicy.literal("Space",""))
        assertNotNull(VisualKeyboardPolicy.literal("Backspace",""))
        assertNull(VisualKeyboardPolicy.literal("a","Enter"))
    }
    @Test fun duplicateAncestorLabelsCanOnlyCorroborateTheSamePurpose() {
        assertNull(VisualKeyboardPolicy.refusal(frame.copy(nodes=frame.nodes+key.copy(id=3,parent=2)),key,"keyboard"))
        for (label in listOf("b", "Enter", "Send", "Shift"))
            assertNotNull(VisualKeyboardPolicy.refusal(frame.copy(nodes=frame.nodes+key.copy(id=3,text=label,keyboardKey=label)),key,"keyboard"))
    }
}
