
var JsUtils = (function(){

    function fillAccessLinks() {
        var prefix = window.location.protocol + "//" + window.location.host;
        $("a[data-role='accessLink']").each(
            function(){
                var $this = $(this);
                var link = prefix + jsRoutes.controllers.InterviewCtrl.accessByLink($this.data("link")).url;
                $this.text(link);
                $this.attr("href", link);
        });
    }

    return {
        fillHereSpans: function(){ $("span[data-role='access']").text(window.location.href); },
        fillAccessLinks: fillAccessLinks
    };
})();

