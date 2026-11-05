package com.asl.backend.dictionary.application.port.out;
import com.asl.backend.dictionary.application.port.out.DictionaryRepository.Position;
public interface DictionaryCursor {
    String encode(String scope, Position position);
    Position decode(String scope, String value);
}
