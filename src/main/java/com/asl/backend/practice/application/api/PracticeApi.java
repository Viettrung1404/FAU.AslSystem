package com.asl.backend.practice.application.api;
import com.asl.backend.learning.application.api.PracticeContentReader.Source;
import java.util.*;
public interface PracticeApi {
    Map<String,Object> availability(List<Source> sources);
    Map<String,Object> generate(String mode,Integer itemCount,List<Source> sources);
}
