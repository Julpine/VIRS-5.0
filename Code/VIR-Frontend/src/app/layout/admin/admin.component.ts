import { Component, OnInit, Input, NgModule } from '@angular/core';
import { IWord, AdminService } from '../../shared'
import { Router } from '@angular/router';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';



@Component({
  selector: 'app-admin',
  templateUrl: './admin.component.html',
  styleUrls: ['./admin.component.scss']
})
export class AdminComponent implements OnInit {
  currentJustify = 'start';
  processing = false;
  error = false;
  errorAdd = false;
  word: IWord;
  showTable = false;

  @Input() searchArea: string;
  @Input() wordArea: string;
  @Input() idArea: number;

  addWordMessage: string;
  editWordMessage: string;
  deleteWordMessage: string;
  alertWord: string;

  categoryItems: string[] = ['Category...', 'awl','stem', 'hi', 'med', 'low', 'K1', 'K2', 'baw'];
  category: string = this.categoryItems[0];

  sessionHistory: string[] = [];
  index = 1;

  constructor(private _admin: AdminService, public router: Router) { }

  // search the word in database
  searchWord(): void {
    this.processing = true;
    this.error = false;
    this.errorAdd = false;
    this.alertWord = this.searchArea;
    this._admin.getWord(this.searchArea)
      .subscribe
      (res => {
        this.word = res;
        this.processing = false;
        this.showTable = true;
        
      },
      (err: HttpErrorResponse) => {
        if (err.error instanceof Error) {
          console.log('Client-side Error occured');
        } else {
          this.error = true;
          this.processing = false;
          console.log('Server-side Error occured');
        }
      }
      );
  }

  // Add new word to data base
  addWord(wordArea:string, category:string): void {
    console.log('in addword')
    this.processing = true;
    this.error = false;
    this.errorAdd = false;
    this._admin.postWord(wordArea, category)
      .subscribe
      (res => {
        this.processing = false;
        this.sessionHistory[this.index] = wordArea + ' is added to ' + category + ' category.'
        this.index++;
      },
      (err: HttpErrorResponse) => {
        if (err.error instanceof Error) {
          console.log('Client-side Error occured');
        } else {
          this.errorAdd = true;
          this.processing = false;
          console.log('Server-side Error occured');
        }
      }
      );
  }


  // Add new word to data base
  editWord(): void {
    this.processing = true;
    this.error = false;
    this.errorAdd = false;
    this._admin.putWord(this.wordArea, this.category, this.idArea)
      .subscribe
      (res => {
        this.processing = false;
        this.sessionHistory[this.index] = 'Word ID: ' + this.idArea + ' was edited to ' + this.wordArea;
        this.index++;
      },
      (err: HttpErrorResponse) => {
        if (err.error instanceof Error) {
          console.log('Client-side Error occured');
        } else {
          this.error = true;
          this.processing = false;
          console.log('Server-side Error occured');
        }
      }
      );

      
  }

  // Delete the word in database
  deleteWord(): void {
    this.processing = true;
    this.error = false;
    this.errorAdd = false;
    this._admin.deleteWord(this.wordArea)
      .subscribe
      (res => {
        this.processing = false;
        this.sessionHistory[this.index] = this.wordArea + ' was succesfully erased from database.'
        this.index++;
      },
      (err: HttpErrorResponse) => {
        if (err.error instanceof Error) {
          console.log('Client-side Error occured');
        } else {
          // this.error = true;
          this.processing = false;
          console.log('Server-side Error occured');
        }
      }
      );
  }

  fileUploadListener($event:any):void{
    
    this.csvadd($event.target, this.addWord.bind(this), this._admin, this.processing, this.sessionHistory, this.index, this.errorAdd, this.categoryItems);
    console.log('out of it');
    
   }

  
csvadd(csv: any,  callback, _admin, processing, sessionHistory, index, errorAdd, categoryItems ):void { 
    console.log('in csvadd')
    var file:File = csv.files[0];
    var self = this;
    var reader:FileReader = new FileReader();
    var wordArea:string;
    var category:string;
    var array;
    var fields;
    this.processing = true;
    this.error = false;
    this.errorAdd = false;

    console.log('here 2')
 
    reader.readAsText(file);
    
     reader.onloadend =(e)=> {
      var csvData = reader.result;
      console.log(csvData);
      fields = csvData.split('\n');


      fields.forEach(function(element){
        console.log('in loop'); 
        var array = element.split(',');
        console.log(element);
        wordArea=array[0];
        console.log(wordArea);
        console.log(categoryItems[1])
        array[1]=array[1].replace(/[^a-zA-Z ]/g, "");
       if(array[1]==='awl'){
         category=categoryItems[1];
       }
       if(array[1]==='stem'){
        category=categoryItems[2];
      }
      if(array[1]==='hi'){
        category=categoryItems[3];
      }
      if(array[1]==='med'){
        category=categoryItems[4];
      }
      if(array[1]==='low'){
        category=categoryItems[5];
      }
      if(array[1]==='K1'){
        category=categoryItems[6];
      }
      if(array[1]==='K2'){
        category=categoryItems[7];
      }
      if(array[1]==='baw'){
        category=categoryItems[8];
      }

      console.log(category);
        _admin.postWord(wordArea, category)
      .subscribe
      (res => {
        processing = false;
        sessionHistory[index] = array[0] + ' is added to ' + array[1] + ' category.'
        index++;
      },
      (err: HttpErrorResponse) => {
        if (err.error instanceof Error) {
          console.log('Client-side Error occured');
        } else {
          errorAdd = true;
          processing = false;
          console.log('Server-side Error occured');
        }
      }
      );
        
      }
    
    ,
    reader.onerror = function () {
             console.log('Unable to read ' + file);
         })
  }}

  ngOnInit() {
  }

}
