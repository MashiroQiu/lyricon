import io.github.proify.lyricon.statusbarlyric.CameraWidth;
import java.util.Random;
public class CameraWidthCheck {
    private static void check(int edge,int anchor,int gap,int expected) {
        if (CameraWidth.available(edge,anchor,gap)!=expected) throw new AssertionError();
    }
    public static void main(String[] args) {
        check(500,40,12,448); check(540,120,16,404);
        check(250,300,12,0); check(500,40,-2,460);
        check(Integer.MAX_VALUE,Integer.MIN_VALUE,0,Integer.MAX_VALUE);
        Random r=new Random(102);
        for(int i=0;i<10000;i++) {
            int anchor=r.nextInt(800), edge=r.nextInt(1400), gap=r.nextInt(40);
            int width=CameraWidth.available(edge,anchor,gap);
            if(width<0 || (width>0 && anchor+width>edge-gap)) throw new AssertionError();
        }
        System.out.println("Camera width: 5 fixtures and 10000 bounds checks passed");
    }
}
