(function() {
  var sidebar = document.getElementById("sidebar");
  var target = sidebar.offsetTop + sidebar.clientHeight + 60;
  console.log(target);

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
    if(getScrollTop() > target) {
      console.log("fixed");
      sidebar.style.top = "30px";
      sidebar.style.position = "fixed";
    } else {
      console.log("relative");
      sidebar.style.top = "";
      sidebar.style.position = "absolute";
    }
  }

  window.onscroll = setSidebarPosition;
})();