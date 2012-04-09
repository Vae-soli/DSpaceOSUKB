// # Visualize Data
//
// Simple script to help visualize data generated by elastic search.

// Firstly we wrap our code in a closure to keep variables local.
(function (context) {

    // ### Chart Maker
    //
    // Create a helper module called chart maker that allows us to specify and
    // draw charts.
    (function ($) {
        context.ChartMaker = function() {
          var chartMaker = {};
          // A place to store charts to draw later.
          chartMaker.charts = {};

          // A shortcut to the google.visualization.DataTable function.
          chartMaker.chartData = function () {
            return new google.visualization.DataTable();
          };

          // The `addChart` function is used to add chart data to `ChartMaker`.
          // It does two things: Creates a div under the specified parent div
          // to put the chart in and add a new chart to `ChartMaker`'s internal
          // list of charts.
          //
          // `addChart` takes an object (`userConfig`) that should have the
          // following keys.
          //
          // * `entries` {object} The entries we use to create the chart.
          // * `name` {string} A nice name to give the chart so the user can reference it
          // later.
          // * `includeTotal` {boolean} Whether or not to include the total in
          // each row.
          // * `chartData` {object} An instance of ChartMaker.chartData containing the data for
          // the chart being added.
          // * `keyField` {string} The name of the key to use on entries.
          // * `valueField` {string} The name of the value to use on entries.
          // * `parentElement` {string} The parent div to create the chart under
          // * `chartType` {string} The key specifying what type of chart to
          // use from `google.visualization`.
          chartMaker.addChart = function (userConfig) {
            var c = $.extend({
                entries: [],
                name: '',
                includeTotal: false,
                chartData: null,
                keyField: 'term',
                valueField: 'count',
                parentElement: 'aspect_dashboard_ElasticSearchStatsViewer_div_chart_div',
                chartType: 'GeoChart'
            }, userConfig);

            // `dataValue` will eventually become the rows of data in our
            // chart.
            var dataValue = [];
            var total = 0;

            // For each entry construct a vector to add to push onto
            // `dataValue`.
            $.each(c.entries, function(index, entry) {
              newEntry = [];
              if(c.chartType == 'LineChart') {
                newEntry.push(new Date(entry[c.keyField]));
              } else {
                newEntry.push(entry[c.keyField]);
              }
              newEntry.push(entry[c.valueField]);
              if(c.includeTotal) {
                total += entry.count;
                newEntry.push(total);
              }
              dataValue.push(newEntry);
            });

            // Add rows (`dataValue`) to the chartData.
            c.chartData.addRows(dataValue);

            // Add a child element
            var par = $('#' + c.parentElement);
            par.append("<div style='height:280px; width:750px;' " +
                "id='dspaceChart_" + c.name + "'> </div>");
            this.charts[c.name] = {
              chart: new google.visualization[c.chartType](
                document.getElementById("dspaceChart_" + c.name)),
              data: c.chartData,
              options: c.options
            };
          };

          // `drawChart` takes a chart from ChartMaker's internal `charts` array
          // (specified by the `name` parameter) and draws that chart.
          chartMaker.drawChart = function(name, globalOptions) {
            if (typeof globalOptions === 'undefined') {
              globalOptions = {};
            }
            var cobj = this.charts[name];

            // Allow the user to overwrite data with a passed in options.
            var data = cobj.data;
            if ('data' in globalOptions) {
              data = globalOptions.data;
            }

            // Merge the Global Options with the local options for the chart
            var combinedOptions = $.extend(globalOptions, cobj.options);
            // Draw the named chart!
            cobj.chart.draw(data, combinedOptions);
          };

          // `drawAllCharts` simply loops through the defined charts and draws
          // each one.
          chartMaker.drawAllCharts = function (options) {
            for (var name in this.charts) {
              this.drawChart(name, options);
            }
          };
          return chartMaker;
        };
    })(jQuery);

    // ## Now some user level code. . .
    // Load the visualization Library
    google.load('visualization', '1',{'packages':['annotatedtimeline', 'geochart', 'corechart', 'table']});

    // Set the callback for once the visualization library has loaded and make
    // sure the DOM has loaded as well.
    google.setOnLoadCallback(function () {
      jQuery(document).ready(function ($) {
        //Create a ChartMaker instance.
        var chartMaker = new ChartMaker();

        // Get data from elastic that has been dumped on the page.
        var elasticJSON = $.parseJSON($('#aspect_dashboard_ElasticSearchStatsViewer_field_response').val());

        // `function chartDataHelper` creates a chartData object from a few
        // parameters.
        function chartDataHelper(type, textKey, textValue, includeTotal, textTotal) {
          // Put data from Elastic response into a ChartData object
          var main_chart_data = chartMaker.chartData();

          if(type == 'date') {
            main_chart_data.addColumn('date', textKey);
          } else {
            main_chart_data.addColumn('string', textKey);
          }

          main_chart_data.addColumn('number', textValue);
          if(includeTotal) {
            main_chart_data.addColumn('number', textTotal);
          }

          return main_chart_data;
        }

        // Set the title for the charts.
        var options = { title : 'Views per DSpaceObject Type' };

        // ### Start adding charts!
        //
        // Use a helper to do all the work to create the
        // associated charts data tables.
        // There is one parent div chart_div, and we will append child divs for each chart.

        // Add a chart to show total downloads.
          var optionsDownloads = {title: 'Number of File Downloads to the Collection/Community'};
        /*
        if(elasticJSON.facets["monthly_downloads"] !== undefined) {
            var chartDataTotal = chartDataHelper('date', 'Date', 'File Downloads', true, 'Total Downloads');
            chartMaker.addChart({
                entries: elasticJSON.facets.monthly_downloads.entries,
                name: 'downloadsWithTotal',
                includeTotal: true,
                chartData: chartDataTotal,
                keyField: 'time',
                chartType: 'LineChart',
                options: optionsDownloads});
          }
        */


        // Add a chart to show monthly downloads (without the total).
        if(elasticJSON.facets["monthly_downloads"] !== undefined) {
            var chartDataNoTotal = chartDataHelper('date', 'Date', 'File Downloads', false, 'Total Downloads');
            chartMaker.addChart({
                entries: elasticJSON.facets.monthly_downloads.entries,
                name: 'downloadsMonthly',
                chartData: chartDataNoTotal,
                keyField: 'time',
                chartType: 'LineChart',
                options: optionsDownloads});
        }

        // Add a chart to show downloads from various countries.
        if(elasticJSON.facets["top_countries"] !== undefined) {
            var chartDataGeo = chartDataHelper('string', 'Country', 'Downloads', false, 'Total');
            chartMaker.addChart({
                entries: elasticJSON.facets.top_countries.terms,
                name: 'topCountries',
                chartData: chartDataGeo,
                options: options});

            if($('input[name=reportDepth]').val() == "detail") {
                chartMaker.addChart({
                    entries: elasticJSON.facets.top_countries.terms,
                    name: 'topCountriesTable',
                    chartData: chartDataGeo,
                    options:options,
                    chartType: 'Table'
                });
            }
        }


        // Add a chart to show downloads from various countries.
        if(elasticJSON.facets["top_US_cities"] !== undefined) {
            var chartDataGeoUS = chartDataHelper('string', 'City', 'Downloads', false, 'Total');
            var optionsUS = {region : 'US', displayMode : 'markers', resolution : 'provinces', magnifyingGlass : {enable: true, zoomFactor: 7.5} };
            chartMaker.addChart({
                entries: elasticJSON.facets.top_US_cities.terms,
                name: 'topUSCities',
                chartData: chartDataGeoUS,
                options: optionsUS});
        }

        // Add a pie chart that shows top DSO Types usage.
        if(elasticJSON.facets["top_types"] !== undefined) {
            var chartDataPie = chartDataHelper('string', 'Type', 'Views', false, '');
            chartMaker.addChart({
                entries: elasticJSON.facets.top_types.terms,
                name: 'topTypes',
                chartData: chartDataPie,
                chartType: 'PieChart',
                options: options});
        }

        // Finally, we draw all of the charts.
        chartMaker.drawAllCharts();
      });

      //Set Titles to Charts that cannot otherwise set titles automatically (geocharts).
      var baseURLStats = $('input[name=baseURLStats]').val();
      $('<p><a href="'+ baseURLStats + '/itemsAdded">Number of Items Added to the Community</a></p>').insertBefore('#aspect_dashboard_ElasticSearchStatsViewer_table_itemsAddedGrid');
      $('<p><a href="'+ baseURLStats + '/filesAdded">Number of Files in the Community</a></p>').insertBefore('#aspect_dashboard_ElasticSearchStatsViewer_table_filesInContainer-grid');
      $('<p><a href="'+ baseURLStats + '/fileDownloads">Number of File Downloads to the Collection/Community</a></p>').insertBefore('#dspaceChart_downloadsMonthly');
      $('<p><a href="'+ baseURLStats + '/topCountries">Countries with most Downloads to the Collection/Community</a></p>').insertBefore('#dspaceChart_topCountries');
      $('<p><a href="'+ baseURLStats + '/topUSCities">US Cities with Most Downloads to the Collection/Community</a></p>').insertBefore('#dspaceChart_topUSCities');

      if($('input[name=reportDepth]').val() == "detail") {
          $('<p><a href="' + baseURLStats + '">Back to Main Summary Statistics for this Collection/Community</a></p>').insertAfter('#aspect_dashboard_ElasticSearchStatsViewer_div_chart_div');
      }
    });
})(this);
