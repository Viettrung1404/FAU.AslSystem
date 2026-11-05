package com.asl.backend.dictionary;
import com.asl.backend.dictionary.adapter.in.web.CollectionLandingController;
import com.asl.backend.dictionary.application.api.DictionaryApi;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class CollectionLandingTest {
    @Test void premiumLandingEscapesAuthoredTextAndNeverRequestsMembership() {
        var api=mock(DictionaryApi.class); var id=UUID.randomUUID();
        when(api.collection(id)).thenReturn(Map.of("name","<script>alert(1)</script>","description","A & B","isPremium",true,"shareUrl","https://share.example.org/collections/"+id));
        var response=new CollectionLandingController(api).landing(id.toString());
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("&lt;script&gt;","A &amp; B","Premium").doesNotContain("<script>","video","assetId");
        verify(api).collection(id); verifyNoMoreInteractions(api);
    }
    @Test void invalidAndRemovedCollectionsReturnUnavailablePage() {
        var api=mock(DictionaryApi.class); var controller=new CollectionLandingController(api);
        assertThat(controller.landing("bad").getStatusCode().value()).isEqualTo(404); verifyNoInteractions(api);
        var id=UUID.randomUUID(); when(api.collection(id)).thenThrow(new ApplicationFailure(ApplicationFailure.Kind.NOT_FOUND,"NOT_FOUND","missing"));
        assertThat(controller.landing(id.toString()).getStatusCode().value()).isEqualTo(404);
    }
}
