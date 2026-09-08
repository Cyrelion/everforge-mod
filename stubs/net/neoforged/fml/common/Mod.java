package net.neoforged.fml.common;
import java.lang.annotation.*;
import net.neoforged.api.distmarker.Dist;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
    String value();
    Dist[] dist() default {Dist.CLIENT, Dist.DEDICATED_SERVER};
}
