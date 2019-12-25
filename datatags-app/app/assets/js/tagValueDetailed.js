/* jshint esversion:6 */

const UP   = "fa-chevron-circle-up";
const DOWN = "fa-chevron-circle-down";

function toggleBigNote(button, divId) {
    $("#" + divId).slideToggle();
    const iconEmt = button.getElementsByTagName("i")[0];
    if ( iconEmt.className.indexOf(UP) >= 0 ) {
        iconEmt.classList.remove(UP);
        iconEmt.classList.add(DOWN);
    } else {
        iconEmt.classList.remove(DOWN);
        iconEmt.classList.add(UP);
    }
}