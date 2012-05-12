(function() {
  var sidebar = document.getElementById("sidebar");

  function getScrollTop(){
    if(typeof pageYOffset!= 'undefined'){
      return pageYOffset;//most browsers
    }
    else{
      var B= document.body; //IE 'quirks'
      var D= document.documentElement; //IE with doctype
      D= (D.clientHeight)? D: B;
      return D.scrollTop;
    }
  }

  function setSidebarPosition() {
    if(getScrollTop() > 350) {
      sidebar.style.top = "50px";
      sidebar.style.position = "fixed";
    } else {
      sidebar.style.top = "";
      sidebar.style.position = "absolute";
    }
  }

  window.onscroll = setSidebarPosition;
})();