function toggleBigNote(btn) {
    var p  = btn.parentElement;
    console.log(p);
    $(p).find(".bigNote").slideToggle();
}