package local.rayo.launcher;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;

/**
 * GabOS Keyboard: a small on-screen keyboard usable both with the projector's
 * pointer (click any key) and with a D-pad (move the highlight, press OK).
 * It has no dictionary, network access, history or learning.
 */
public class KeyboardService extends InputMethodService {
    private static final String SHIFT="⇧", DELETE="⌫", SPACE="space", DONE="Done", SYMBOLS="?123", LETTERS="abc";
    private static final String[][] ALPHA={
        {"1","2","3","4","5","6","7","8","9","0"},
        {"q","w","e","r","t","y","u","i","o","p"},
        {"a","s","d","f","g","h","j","k","l","ñ"},
        {SHIFT,"z","x","c","v","b","n","m",DELETE},
        {SYMBOLS,"@",".",SPACE,"-","_",DONE}};
    private static final String[][] SYMBOL={
        {"1","2","3","4","5","6","7","8","9","0"},
        {"!","\"","#","$","%","&","/","(",")","="},
        {"?","¿","¡","'","+","*",":",";",",","."},
        {"<",">","[","]","{","}","\\","|",DELETE},
        {LETTERS,"@","~",SPACE,"-","_",DONE}};
    private final ArrayList<ArrayList<TextView>> grid=new ArrayList<>();
    private LinearLayout board;
    private boolean symbols=false, shift=false;
    private int row=1, column=0;

    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}

    @Override public boolean onEvaluateFullscreenMode(){return false;}

    @Override public View onCreateInputView(){
        board=new LinearLayout(this);board.setOrientation(LinearLayout.VERTICAL);
        board.setBackgroundColor(0xf2111418);board.setPadding(dp(150),dp(10),dp(150),dp(14));
        build();return board;
    }

    @Override public void onStartInputView(EditorInfo info, boolean restarting){
        super.onStartInputView(info,restarting);
        symbols=false;shift=info!=null&&(info.inputType&EditorInfo.TYPE_TEXT_FLAG_CAP_WORDS)!=0;row=1;column=0;
        if(board!=null)build();
    }

    private void build(){
        board.removeAllViews();grid.clear();
        String[][] layout=symbols?SYMBOL:ALPHA;
        for(int r=0;r<layout.length;r++){
            LinearLayout line=new LinearLayout(this);line.setGravity(Gravity.CENTER);
            ArrayList<TextView> keys=new ArrayList<>();
            for(int c=0;c<layout[r].length;c++){
                final String key=layout[r][c];final int kr=r,kc=c;
                String shown=key.length()==1&&shift&&!symbols?key.toUpperCase():key;
                TextView view=new TextView(this);view.setText(shown);view.setTextSize(key.length()>1&&!key.equals(SHIFT)&&!key.equals(DELETE)?15:19);
                view.setTextColor(0xfff1f3f4);view.setGravity(Gravity.CENTER);view.setTypeface(Typeface.DEFAULT);
                view.setClickable(true);
                view.setOnClickListener(v->{row=kr;column=kc;press(key);});
                view.setOnHoverListener((v,event)->{if(row!=kr||column!=kc){row=kr;column=kc;highlight();}return false;});
                float weight=key.equals(SPACE)?4f:key.length()>1&&!key.equals(SHIFT)&&!key.equals(DELETE)?1.6f:key.equals(SHIFT)||key.equals(DELETE)?1.5f:1f;
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(44),weight);p.setMargins(dp(3),dp(3),dp(3),dp(3));
                line.addView(view,p);keys.add(view);
            }
            board.addView(line,new LinearLayout.LayoutParams(-1,-2));grid.add(keys);
        }
        row=Math.min(row,grid.size()-1);column=Math.min(column,grid.get(row).size()-1);highlight();
    }

    private void highlight(){
        for(int r=0;r<grid.size();r++)for(int c=0;c<grid.get(r).size();c++){
            boolean selected=r==row&&c==column;TextView view=grid.get(r).get(c);
            boolean lit=shift&&!symbols&&SHIFT.contentEquals(view.getText());
            GradientDrawable shape=new GradientDrawable();shape.setCornerRadius(dp(6));
            shape.setColor(selected?0xffe8eaed:lit?0xff3c5a99:0xff2a2f37);view.setBackground(shape);
            view.setTextColor(selected?0xff111418:0xfff1f3f4);
        }
    }

    /** Visible confirmation that a key was taken: brief accent fill and a small press. */
    private void flash(){
        if(row>=grid.size()||column>=grid.get(row).size())return;
        final TextView view=grid.get(row).get(column);
        GradientDrawable shape=new GradientDrawable();shape.setCornerRadius(dp(6));shape.setColor(0xff8ab4f8);view.setBackground(shape);
        view.setTextColor(0xff111418);
        view.animate().cancel();view.setScaleX(.86f);view.setScaleY(.86f);
        view.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
        view.postDelayed(this::highlight,170);
    }

    private void press(String key){
        flash();
        InputConnection connection=getCurrentInputConnection();
        if(key.equals(SHIFT)){shift=!shift;build();return;}
        if(key.equals(SYMBOLS)||key.equals(LETTERS)){symbols=!symbols;build();return;}
        if(connection==null)return;
        if(key.equals(DELETE))connection.deleteSurroundingText(1,0);
        else if(key.equals(SPACE))connection.commitText(" ",1);
        else if(key.equals(DONE)){
            EditorInfo info=getCurrentInputEditorInfo();
            int action=info==null?EditorInfo.IME_ACTION_DONE:info.imeOptions&EditorInfo.IME_MASK_ACTION;
            if(action==EditorInfo.IME_ACTION_NONE||action==EditorInfo.IME_ACTION_UNSPECIFIED||
                    (info!=null&&(info.imeOptions&EditorInfo.IME_FLAG_NO_ENTER_ACTION)!=0))
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
            else connection.performEditorAction(action);
            requestHideSelf(0);return;
        }else{
            connection.commitText(shift&&!symbols?key.toUpperCase():key,1);
            if(shift&&!symbols){shift=false;build();return;}
        }
    }

    /** The input window is not focusable, so D-pad navigation is handled here. */
    @Override public boolean onKeyDown(int code, KeyEvent event){
        if(!isInputViewShown()||grid.isEmpty())return super.onKeyDown(code,event);
        switch(code){
            case KeyEvent.KEYCODE_DPAD_LEFT:column=(column+grid.get(row).size()-1)%grid.get(row).size();break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:column=(column+1)%grid.get(row).size();break;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:{
                int next=row+(code==KeyEvent.KEYCODE_DPAD_UP?-1:1);
                if(next<0||next>=grid.size())return true;
                // Keep the horizontal position when rows have different key counts.
                float center=grid.get(row).get(column).getX()+grid.get(row).get(column).getWidth()/2f;int best=0;float distance=Float.MAX_VALUE;
                for(int c=0;c<grid.get(next).size();c++){View v=grid.get(next).get(c);float d=Math.abs(v.getX()+v.getWidth()/2f-center);if(d<distance){distance=d;best=c;}}
                row=next;column=best;break;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if(event.getRepeatCount()==0)press(symbols?SYMBOL[row][column]:ALPHA[row][column]);
                return true;
            default:return super.onKeyDown(code,event);
        }
        highlight();return true;
    }

    @Override public boolean onKeyUp(int code, KeyEvent event){
        if(isInputViewShown())switch(code){
            case KeyEvent.KEYCODE_DPAD_LEFT:case KeyEvent.KEYCODE_DPAD_RIGHT:case KeyEvent.KEYCODE_DPAD_UP:case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:case KeyEvent.KEYCODE_ENTER:case KeyEvent.KEYCODE_NUMPAD_ENTER:return true;
        }
        return super.onKeyUp(code,event);
    }
}
