
var JsUtils = (function(){

    function getPagePrefix() {
        return window.location.protocol + "//" + window.location.host;
    }

    function fillAccessLinks() {
        var prefix = getPagePrefix();
        $("a[data-role='accessLink']").each(
            function(){
                var $this = $(this);
                var link = prefix + jsRoutes.controllers.InterviewCtrl.accessByLink($this.data("link")).url;
                $this.text(link);
                $this.attr("href", link);
        });
    }

    function addHost( id ) {
        $(id).text( getPagePrefix() + $(id).text() );
    }

    return {
        fillHereSpans: function(){ $("span[data-role='access']").text(window.location.href); },
        fillAccessLinks: fillAccessLinks,
        addHost:addHost
    };
})();

